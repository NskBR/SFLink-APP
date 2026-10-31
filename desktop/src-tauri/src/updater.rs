use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{path::PathBuf, sync::{atomic::{AtomicBool, Ordering}, Mutex}, time::Duration};
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::io::AsyncWriteExt;
use tokio_util::sync::CancellationToken;

const REPO: &str = "NskBR/SFLink-APP";
const MAX_SIZE: u64 = 512 * 1024 * 1024;
pub static INSTALLING: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Deserialize)]
struct Asset { name: String, browser_download_url: String, size: u64, digest: Option<String> }
#[derive(Deserialize)]
struct Release { tag_name: String, body: Option<String>, draft: bool, prerelease: bool, assets: Vec<Asset> }
#[derive(Clone)]
struct Approved { asset: Asset, hash: String }
#[derive(Default)]
pub struct Updater { inner: Mutex<Runtime> }
#[derive(Default)]
struct Runtime { approved: Option<Approved>, ready: Option<(PathBuf, String)>, downloading: Option<CancellationToken> }
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo { available: bool, version: String, notes: String, size: u64, installable: bool }
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress { downloaded: u64, total: u64 }

fn version(value: &str) -> Option<[u64; 3]> {
    let parts: Vec<_> = value.strip_prefix('v').unwrap_or(value).split('.').collect();
    if parts.len() != 3 || parts.iter().any(|p| p.is_empty() || !p.bytes().all(|c| c.is_ascii_digit())) { return None; }
    Some([parts[0].parse().ok()?, parts[1].parse().ok()?, parts[2].parse().ok()?])
}
fn approved_asset(asset: &Asset) -> Option<Approved> {
    let url = reqwest::Url::parse(&asset.browser_download_url).ok()?;
    let hash = asset.digest.as_deref()?.strip_prefix("sha256:")?;
    let name = asset.name.to_ascii_lowercase();
    if url.scheme() != "https" || url.host_str() != Some("github.com")
        || !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some()
        || !url.path().starts_with(&format!("/{REPO}/releases/download/"))
        || !url.path().ends_with(&format!("/{}", asset.name))
        || !name.starts_with("sflink_") || !name.ends_with("_x64-setup.exe")
        || asset.name.contains(['/', '\\']) || asset.size == 0 || asset.size > MAX_SIZE
        || hash.len() != 64 || !hash.bytes().all(|c| c.is_ascii_hexdigit()) { return None; }
    Some(Approved { asset: asset.clone(), hash: hash.to_ascii_lowercase() })
}
fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder().user_agent("SFLink-updater").https_only(true)
        .connect_timeout(Duration::from_secs(15)).timeout(Duration::from_secs(600))
        .redirect(reqwest::redirect::Policy::limited(5)).build().map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn check_for_updates(state: State<'_, Updater>) -> Result<UpdateInfo, String> {
    {
        let mut runtime = state.inner.lock().map_err(|e| e.to_string())?;
        if runtime.downloading.is_some() || INSTALLING.load(Ordering::SeqCst) { return Err("A atualização já está em andamento.".into()); }
        runtime.approved = None;
    }
    let current = env!("CARGO_PKG_VERSION");
    let response = client()?.get(format!("https://api.github.com/repos/{REPO}/releases/latest")).send().await.map_err(|_| "Não foi possível consultar atualizações. Verifique sua internet.".to_string())?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(UpdateInfo { available: false, version: current.into(), notes: "Ainda não há uma release pública.".into(), size: 0, installable: false });
    }
    let release: Release = response.error_for_status().map_err(|e| format!("Falha ao consultar o GitHub: {e}"))?.json().await.map_err(|_| "Resposta de atualização inválida.".to_string())?;
    let latest = version(&release.tag_name).ok_or("A release precisa usar uma versão no formato v0.2.1.")?;
    let available = !release.draft && !release.prerelease && latest > version(current).ok_or("Versão local inválida.")?;
    let approved = if available { release.assets.iter().filter(|asset| asset.name == format!("SFLink_{}_x64-setup.exe", release.tag_name.trim_start_matches('v'))).find_map(approved_asset) } else { None };
    let info = UpdateInfo { available, version: release.tag_name.trim_start_matches('v').into(), notes: release.body.unwrap_or_default(), size: approved.as_ref().map(|a| a.asset.size).unwrap_or(0), installable: approved.is_some() };
    state.inner.lock().map_err(|e| e.to_string())?.approved = approved;
    Ok(info)
}

#[tauri::command]
pub fn cancel_update(state: State<'_, Updater>) -> Result<(), String> {
    if let Some(cancel) = &state.inner.lock().map_err(|e| e.to_string())?.downloading { cancel.cancel(); }
    Ok(())
}

#[tauri::command]
pub async fn download_update(app: AppHandle, state: State<'_, Updater>) -> Result<(), String> {
    let cancel = CancellationToken::new();
    let approved = {
        let mut runtime = state.inner.lock().map_err(|e| e.to_string())?;
        if runtime.downloading.is_some() || INSTALLING.load(Ordering::SeqCst) { return Err("A atualização já está em andamento.".into()); }
        let approved = runtime.approved.clone().ok_or("Verifique a atualização antes de baixar.")?;
        runtime.ready = None; runtime.downloading = Some(cancel.clone()); approved
    };
    let result = tokio::select! { _ = cancel.cancelled() => Err("Download cancelado.".into()), result = fetch(&app, &approved) => result };
    let mut runtime = state.inner.lock().map_err(|e| e.to_string())?;
    runtime.downloading = None;
    if let Ok(path) = &result { runtime.ready = Some((path.clone(), approved.hash)); }
    result.map(|_| ())
}
async fn fetch(app: &AppHandle, approved: &Approved) -> Result<PathBuf, String> {
    let root = app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("updates");
    std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;
    // A scoped temporary file is removed on failure or cancellation.
    let temporary = tempfile::NamedTempFile::new_in(&root).map_err(|e| e.to_string())?;
    let mut output = tokio::fs::File::create(temporary.path()).await.map_err(|e| e.to_string())?;
    let response = client()?.get(&approved.asset.browser_download_url).send().await.map_err(|e| e.to_string())?.error_for_status().map_err(|e| e.to_string())?;
    let mut stream = response.bytes_stream(); let mut hash = Sha256::new(); let mut downloaded = 0;
    let mut emitted = std::time::Instant::now();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| e.to_string())?; downloaded += chunk.len() as u64;
        if downloaded > approved.asset.size { return Err("O instalador excedeu o tamanho publicado.".into()); }
        hash.update(&chunk); output.write_all(&chunk).await.map_err(|e| e.to_string())?;
        if emitted.elapsed() >= Duration::from_millis(150) {
            let _ = app.emit("update-progress", Progress { downloaded, total: approved.asset.size }); emitted = std::time::Instant::now();
        }
    }
    output.sync_all().await.map_err(|e| e.to_string())?; drop(output);
    if downloaded != approved.asset.size || format!("{:x}", hash.finalize()) != approved.hash { return Err("A verificação SHA-256 do instalador falhou.".into()); }
    let destination = root.join(&approved.asset.name);
    temporary.persist(&destination).map_err(|e| e.to_string())?;
    let _ = app.emit("update-progress", Progress { downloaded, total: approved.asset.size });
    Ok(destination)
}

#[tauri::command]
pub async fn install_update(app: AppHandle, state: State<'_, Updater>, files: State<'_, crate::AppState>) -> Result<(), String> {
    if cfg!(debug_assertions) { return Err("Instalação disponível na versão compilada do aplicativo.".into()); }
    let (installer, hash) = {
        let runtime = state.inner.lock().map_err(|e| e.to_string())?;
        if runtime.downloading.is_some() { return Err("Aguarde o download terminar.".into()); }
        runtime.ready.clone().ok_or("Baixe a atualização primeiro.")?
    };
    {
        let transfers = files.transfers.lock().map_err(|e| e.to_string())?;
        if !transfers.is_empty() { return Err("Aguarde as transferências terminarem antes de instalar.".into()); }
        if INSTALLING.swap(true, Ordering::SeqCst) { return Err("A instalação já está iniciando.".into()); }
    }
    let result = prepare_install(&app, &installer, &hash).await;
    if result.is_err() { INSTALLING.store(false, Ordering::SeqCst); }
    result
}
async fn prepare_install(app: &AppHandle, installer: &std::path::Path, hash: &str) -> Result<(), String> {
    let mut random = [0u8; 16]; getrandom::fill(&mut random).map_err(|e| e.to_string())?;
    let folder = app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("updates").join(random.iter().map(|v| format!("{v:02x}")).collect::<String>());
    std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
    let executable = std::env::current_exe().map_err(|e| e.to_string())?;
    let destination = executable.parent().ok_or("Pasta do aplicativo inválida.")?;
    let ready = folder.join("ready"); let go = folder.join("go"); let error = folder.join("error");
    let script = folder.join("install.ps1"); let config = folder.join("config.json");
    std::fs::write(&script, format!("\u{feff}{}", include_str!("update-helper.ps1"))).map_err(|e| e.to_string())?;
    let data = serde_json::json!({ "installer": installer, "sha256": hash, "parent": std::process::id(), "parentStart": parent_start_ticks()?, "destination": destination, "executable": destination.join("wifi-files.exe"), "ready": ready, "go": go, "error": error });
    std::fs::write(&config, serde_json::to_vec(&data).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let mut command = std::process::Command::new("powershell.exe");
    command.args(["-NoProfile", "-STA", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File"]).arg(&script).arg("-ConfigPath").arg(&config);
    #[cfg(windows)] { use std::os::windows::process::CommandExt; command.creation_flags(0x08000000); }
    let mut helper = command.spawn().map_err(|e| format!("Não foi possível abrir a tela de atualização: {e}"))?;
    let deadline = std::time::Instant::now() + Duration::from_secs(45);
    loop {
        if error.exists() { let _ = helper.kill(); return Err(std::fs::read_to_string(error).unwrap_or_else(|_| "Falha na atualização.".into())); }
        if ready.exists() { break; }
        if std::time::Instant::now() > deadline || helper.try_wait().map_err(|e| e.to_string())?.is_some() { let _ = helper.kill(); return Err("A tela de atualização não ficou pronta. O SFLink continuará aberto.".into()); }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    if let Err(error) = std::fs::write(go, "install") { let _ = helper.kill(); return Err(error.to_string()); }
    app.exit(0); Ok(())
}
fn parent_start_ticks() -> Result<String, String> {
    // Compare process creation time so a reused PID never blocks the installer.
    #[cfg(windows)] {
        use std::os::windows::process::CommandExt;
        let output = std::process::Command::new("powershell.exe").args(["-NoProfile", "-Command", &format!("(Get-Process -Id {}).StartTime.ToUniversalTime().Ticks.ToString()", std::process::id())]).creation_flags(0x08000000).output().map_err(|e| e.to_string())?;
        if !output.status.success() { return Err("Não foi possível identificar o processo atual.".into()); }
        Ok(String::from_utf8_lossy(&output.stdout).trim().into())
    }
    #[cfg(not(windows))] { Ok(String::new()) }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn versions_are_numeric_and_stable() {
        assert!(version("v0.10.0") > version("0.2.9"));
        for bad in ["TESTE", "1.0.0-beta", "1.0", "1.0.0.1", "1..0"] { assert!(version(bad).is_none()); }
    }
    #[test] fn refuses_untrusted_or_unverified_installers() {
        let mut asset = Asset { name: "SFLink_0.3.0_x64-setup.exe".into(), browser_download_url: "https://github.com/NskBR/SFLink-APP/releases/download/v0.3.0/SFLink_0.3.0_x64-setup.exe".into(), size: 100, digest: Some(format!("sha256:{}", "a".repeat(64))) };
        assert!(approved_asset(&asset).is_some());
        asset.digest = None; assert!(approved_asset(&asset).is_none()); asset.digest = Some(format!("sha256:{}", "a".repeat(64)));
        asset.browser_download_url = asset.browser_download_url.replace("NskBR/SFLink-APP", "other/project"); assert!(approved_asset(&asset).is_none());
    }
}
