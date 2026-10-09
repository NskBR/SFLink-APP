use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, State};
use tauri_plugin_opener::OpenerExt;
use tokio::io::AsyncWriteExt;
use tokio_util::{io::ReaderStream, sync::CancellationToken};
mod updater;
mod remembered;
use remembered::{connect_remembered, connection_health, forget_remembered, remembered_devices};

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    name: String,
    path: String,
    is_dir: bool,
    size: u64,
    modified: Option<u64>,
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Listing {
    path: String,
    parent: Option<String>,
    entries: Vec<Entry>,
}
#[derive(Serialize)]
struct Shortcut {
    name: String,
    path: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Device {
    name: String,
    root: String,
    free_bytes: u64,
    total_bytes: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairingDocument {
    version: u32,
    address: String,
    token: String,
    certificate_pem: String,
    #[serde(default)]
    remembered: bool,
}
fn parse_pairing(text: &str) -> Result<PairingDocument, String> {
    let document: PairingDocument =
        serde_json::from_str(text).map_err(|_| "Arquivo de conexão inválido.".to_string())?;
    if document.version != 1
        || document.token.len() < 16
        || document.token.len() > 256
        || document.certificate_pem.len() > 16384
    {
        return Err("Dados de conexão incompatíveis.".into());
    }
    let url = reqwest::Url::parse(&document.address).map_err(err)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("Endereço de conexão inválido.".into());
    }
    reqwest::Certificate::from_pem(document.certificate_pem.as_bytes())
        .map_err(|_| "Certificado da conexão inválido.".to_string())?;
    Ok(document)
}
#[tauri::command]
async fn read_pairing(path: String) -> Result<PairingDocument, String> {
    tauri::async_runtime::spawn_blocking(move || {
        if std::fs::metadata(&path).map_err(err)?.len() > 12 * 1024 * 1024 {
            return Err("O arquivo de conexão é grande demais.".into());
        }
        if Path::new(&path)
            .extension()
            .and_then(|p| p.to_str())
            .unwrap_or("")
            .eq_ignore_ascii_case("json")
        {
            return parse_pairing(&std::fs::read_to_string(path).map_err(err)?);
        }
        let reader = image::ImageReader::open(&path)
            .map_err(err)?
            .with_guessed_format()
            .map_err(err)?;
        let (width, height) = reader.into_dimensions().map_err(err)?;
        if width > 8192 || height > 8192 {
            return Err("A imagem é grande demais.".into());
        }
        let pixels = image::ImageReader::open(&path)
            .map_err(err)?
            .with_guessed_format()
            .map_err(err)?
            .decode()
            .map_err(err)?
            .to_luma8();
        let mut prepared = rqrr::PreparedImage::prepare(pixels);
        for grid in prepared.detect_grids() {
            if let Ok((_, text)) = grid.decode() {
                if let Ok(document) = parse_pairing(&text) {
                    return Ok(document);
                }
            }
        }
        Err("Não encontrei um QR code de conexão válido nesta imagem.".into())
    })
    .await
    .map_err(err)?
}
#[derive(Clone)]
struct Remote {
    client: reqwest::Client,
    base: reqwest::Url,
    token: String,
}
#[derive(Default)]
struct AppState {
    remote: Mutex<Option<Remote>>,
    transfers: Mutex<HashMap<String, CancellationToken>>,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Progress {
    id: String,
    transferred: u64,
    total: u64,
}

fn err(e: impl std::fmt::Display) -> String {
    e.to_string()
}
fn valid_name(name: &str) -> Result<(), String> {
    if name.is_empty()
        || name.trim() != name
        || name.ends_with('.')
        || name
            .chars()
            .any(|c| c.is_control() || "<>:\"/\\|?*".contains(c))
        || name == "."
        || name == ".."
    {
        return Err("Nome inválido. Evite caracteres especiais e espaços nas extremidades.".into());
    }
    let stem = name.split('.').next().unwrap_or("").to_ascii_uppercase();
    if ["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
        || (stem.len() == 4
            && (stem.starts_with("COM") || stem.starts_with("LPT"))
            && matches!(stem.as_bytes()[3], b'1'..=b'9'))
    {
        return Err("Esse nome é reservado pelo Windows.".into());
    }
    Ok(())
}
#[tauri::command]
fn shortcuts() -> Vec<Shortcut> {
    let mut out = Vec::new();
    for (name, path) in [
        ("Início", dirs::home_dir()),
        ("Downloads", dirs::download_dir()),
        ("Músicas", dirs::audio_dir()),
        ("Documentos", dirs::document_dir()),
        ("Imagens", dirs::picture_dir()),
    ] {
        if let Some(path) = path.filter(|p| p.is_dir()) {
            out.push(Shortcut {
                name: name.into(),
                path: path.to_string_lossy().into(),
            });
        }
    }
    #[cfg(windows)]
    for letter in b'A'..=b'Z' {
        let path = format!("{}:\\", letter as char);
        if Path::new(&path).is_dir() {
            out.push(Shortcut {
                name: format!("Unidade {}:", letter as char),
                path,
            });
        }
    }
    out
}
fn read_listing(path: &str) -> Result<Listing, String> {
    let base = std::fs::canonicalize(path).map_err(err)?;
    let display = |p: &Path| p.to_string_lossy().trim_start_matches(r"\\?\").to_string();
    let mut entries = Vec::new();
    for item in std::fs::read_dir(&base).map_err(err)? {
        let item = item.map_err(err)?;
        let Ok(meta) = item.metadata() else {
            continue;
        };
        entries.push(Entry {
            name: item.file_name().to_string_lossy().into(),
            path: display(&item.path()),
            is_dir: meta.is_dir(),
            size: if meta.is_dir() { 0 } else { meta.len() },
            modified: meta
                .modified()
                .ok()
                .and_then(|v| v.duration_since(UNIX_EPOCH).ok())
                .map(|v| v.as_secs()),
        });
    }
    entries.sort_by(|a, b| {
        b.is_dir
            .cmp(&a.is_dir)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    Ok(Listing {
        path: display(&base),
        parent: base.parent().map(display),
        entries,
    })
}
#[tauri::command]
async fn local_list(path: String) -> Result<Listing, String> {
    tauri::async_runtime::spawn_blocking(move || read_listing(&path))
        .await
        .map_err(err)?
}
#[tauri::command]
async fn local_mkdir(parent: String, name: String) -> Result<(), String> {
    valid_name(&name)?;
    tokio::fs::create_dir(Path::new(&parent).join(name))
        .await
        .map_err(err)
}
#[tauri::command]
async fn local_rename(path: String, name: String) -> Result<(), String> {
    valid_name(&name)?;
    let path = PathBuf::from(path);
    let target = path
        .parent()
        .ok_or("Não é possível renomear a raiz.")?
        .join(name);
    if target.exists() {
        return Err("Já existe um arquivo ou pasta com esse nome.".into());
    }
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        tauri::async_runtime::spawn_blocking(move || {
            let source: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            let destination: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
            // MoveFileW fails if the destination exists, including a concurrent creation.
            let result = unsafe {
                windows_sys::Win32::Storage::FileSystem::MoveFileW(
                    source.as_ptr(),
                    destination.as_ptr(),
                )
            };
            if result == 0 {
                Err(err(std::io::Error::last_os_error()))
            } else {
                Ok(())
            }
        })
        .await
        .map_err(err)?
    }
    #[cfg(not(windows))]
    tokio::fs::rename(path, target).await.map_err(err)
}
#[tauri::command]
async fn local_trash(paths: Vec<String>) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || trash::delete_all(paths).map_err(err))
        .await
        .map_err(err)?
}
#[tauri::command]
fn local_open(app: AppHandle, path: String) -> Result<(), String> {
    app.opener().open_path(path, None::<String>).map_err(err)
}
fn remote(state: &AppState) -> Result<Remote, String> {
    state
        .remote
        .lock()
        .map_err(err)?
        .clone()
        .ok_or("Conecte um celular primeiro.".into())
}
impl Remote {
    fn url(&self, endpoint: &str) -> Result<reqwest::Url, String> {
        self.base.join(endpoint).map_err(err)
    }
    fn request(
        &self,
        method: reqwest::Method,
        endpoint: &str,
    ) -> Result<reqwest::RequestBuilder, String> {
        Ok(self
            .client
            .request(method, self.url(endpoint)?)
            .bearer_auth(&self.token))
    }
}
async fn checked(response: reqwest::Response) -> Result<reqwest::Response, String> {
    if response.status().is_success() {
        return Ok(response);
    }
    let status = response.status();
    Err(match status.as_u16() {
        401 | 403 => "Conexão não autorizada. Verifique o código de acesso.".into(),
        404 => "Arquivo ou pasta não encontrado no celular.".into(),
        409 => "Já existe um arquivo ou pasta com esse nome.".into(),
        507 => "O celular está sem espaço disponível.".into(),
        _ => format!("O celular retornou um erro ({status})."),
    })
}
#[tauri::command]
async fn connect_device(
    state: State<'_, AppState>,
    address: String,
    token: String,
    certificate_path: Option<String>,
    certificate_pem: Option<String>,
) -> Result<Device, String> {
    let mut base = reqwest::Url::parse(address.trim())
        .map_err(|_| "Endereço inválido. Use https://endereço:porta/".to_string())?;
    if base.scheme() != "https"
        || base.host_str().is_none()
        || !base.username().is_empty()
        || base.password().is_some()
        || base.query().is_some()
        || base.fragment().is_some()
    {
        return Err("Use um endereço HTTPS sem usuário, senha ou parâmetros.".into());
    }
    if token.trim().is_empty() {
        return Err("Informe o código de acesso do celular.".into());
    }
    base.set_path("/");
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .connect_timeout(Duration::from_secs(8))
        .redirect(reqwest::redirect::Policy::none());
    if let Some(pem) = certificate_pem.filter(|pem| !pem.is_empty()) {
        let cert = reqwest::Certificate::from_pem(pem.as_bytes())
            .map_err(|_| "Certificado da conexão inválido.".to_string())?;
        builder = builder.add_root_certificate(cert);
    }
    if let Some(path) = certificate_path.filter(|p| !p.is_empty()) {
        let bytes = tokio::fs::read(path).await.map_err(err)?;
        let cert = reqwest::Certificate::from_pem(&bytes)
            .map_err(|_| "Certificado PEM inválido.".to_string())?;
        builder = builder.add_root_certificate(cert);
    }
    let candidate = Remote {
        client: builder.build().map_err(err)?,
        base,
        token: token.trim().into(),
    };
    let response = candidate
        .request(reqwest::Method::GET, "v1/device")?
        .timeout(Duration::from_secs(15))
        .send()
        .await
        .map_err(|e| format!("Não foi possível conectar: {e}"))?;
    let device: Device = checked(response)
        .await?
        .json()
        .await
        .map_err(|_| "O celular enviou uma resposta incompatível.".to_string())?;
    *state.remote.lock().map_err(err)? = Some(candidate);
    Ok(device)
}
#[cfg(test)]
async fn pair_connection(
    address: String,
    code: String,
    emit: impl Fn(String),
) -> Result<PairingDocument, String> {
    pair_connection_client(address, code, None, emit).await
}
async fn pair_connection_client(
    address: String,
    code: String,
    client_id: Option<String>,
    emit: impl Fn(String),
) -> Result<PairingDocument, String> {
    use sha2::{Digest, Sha256};
    let address = address.trim();
    let address = if address.contains("://") {
        address.to_string()
    } else {
        format!("https://{address}")
    };
    let mut base =
        reqwest::Url::parse(&address).map_err(|_| "Informe o IP do celular.".to_string())?;
    let ip: std::net::Ipv4Addr = base
        .host_str()
        .ok_or("Informe o IP do celular.")?
        .parse()
        .map_err(|_| "Use o endereço IPv4 mostrado no Android.".to_string())?;
    if base.scheme() != "https"
        || !(ip.is_private() || ip.is_loopback() || ip.is_link_local())
        || !base.username().is_empty()
        || base.password().is_some()
        || base.query().is_some()
        || base.fragment().is_some()
    {
        return Err("Use o IP local do Android, sem usuário ou parâmetros.".into());
    }
    let code: String = code.chars().filter(|c| !c.is_whitespace()).collect();
    if code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
        return Err("Digite os seis números do código temporário.".into());
    }
    let port = base.port().unwrap_or(8443);
    base.set_port(Some(port)).map_err(|_| "Porta inválida.")?;
    base.set_path("/");
    let mut bootstrap = base.clone();
    bootstrap
        .set_scheme("http")
        .map_err(|_| "Endereço inválido.")?;
    bootstrap
        .set_port(Some(port.checked_add(1).ok_or("Porta inválida.")?))
        .map_err(|_| "Porta inválida.")?;
    bootstrap.set_path("/v1/certificate");
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(8))
        .build()
        .map_err(err)?;
    let mut response = client.get(bootstrap).send().await.map_err(|_| {
        "Não encontrei o celular. Verifique o IP, o Wi-Fi e se o compartilhamento está ativo."
            .to_string()
    })?;
    if !response.status().is_success() {
        return Err("Não foi possível obter a identidade do celular.".into());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| format!("A resposta de identificação do Android foi interrompida: {e:?}"))?
    {
        if bytes.len() + chunk.len() > 16384 {
            return Err("Identidade do celular inválida.".into());
        }
        bytes.extend_from_slice(&chunk);
    }
    let document: serde_json::Value = serde_json::from_slice(&bytes).map_err(|_| "O endereço respondeu com uma identificação incompatível. Confira o IP e atualize o APK Android.".to_string())?;
    let pem = document["certificatePem"]
        .as_str()
        .ok_or("Certificado ausente.")?
        .to_string();
    let certificate = reqwest::Certificate::from_pem(pem.as_bytes()).map_err(err)?;
    let mut random = [0u8; 32];
    getrandom::fill(&mut random).map_err(err)?;
    let nonce: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let digest = Sha256::digest(format!("{pem}{nonce}").as_bytes());
    let verification: String = digest[..8].iter().map(|b| format!("{b:02X}")).collect();
    emit(verification);
    let tls = reqwest::Client::builder()
        .no_proxy()
        .tls_built_in_root_certs(false)
        .add_root_certificate(certificate)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(8))
        .build()
        .map_err(err)?;
    let response = tls
        .post(base.join("v1/pair").map_err(err)?)
        .json(&serde_json::json!({"code":code,"nonce":nonce,"clientId":client_id.unwrap_or_default(),"clientName":std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Meu PC".into())}))
        .timeout(Duration::from_secs(70))
        .send()
        .await
        .map_err(|_| {
            "A conexão não foi aprovada a tempo. Tente novamente com o Android aberto.".to_string()
        })?;
    match response.status().as_u16() {
        200 => {}
        401 => return Err("Código incorreto. Confira os números no Android.".into()),
        403 => return Err("Solicitação recusada ou aprovação expirada.".into()),
        429 => {
            return Err("Código expirado ou limite de tentativas. Gere outro no Android.".into())
        }
        409 => return Err("Já existe uma solicitação aguardando aprovação no Android.".into()),
        _ => return Err("O Android não conseguiu concluir o pareamento.".into()),
    }
    let value: serde_json::Value = response
        .json()
        .await
        .map_err(|e| format!("Não foi possível ler a autorização enviada pelo Android: {e:?}"))?;
    let token = value["token"]
        .as_str()
        .filter(|t| t.len() >= 32 && t.len() <= 256)
        .ok_or("Resposta de pareamento inválida.")?
        .to_string();
    Ok(PairingDocument {
        version: 1,
        address: base.to_string(),
        token,
        certificate_pem: pem,
        remembered: value["remembered"].as_bool().unwrap_or(false),
    })
}
#[tauri::command]
async fn pair_device(
    app: AppHandle,
    state: State<'_, AppState>,
    address: String,
    code: String,
) -> Result<Device, String> {
    let client_id = remembered::client_id(&app)?;
    let document = pair_connection_client(address, code, Some(client_id), |verification| {
        let _ = app.emit("pairing-verification", verification);
    })
    .await?;
    let device = connect_device(
        state,
        document.address.clone(),
        document.token.clone(),
        None,
        Some(document.certificate_pem.clone()),
    )
    .await?;
    if document.remembered {
        remembered::remember(&app, &document, &device)?;
    }
    Ok(device)
}
#[tauri::command]
fn disconnect_device(state: State<'_, AppState>) -> Result<(), String> {
    *state.remote.lock().map_err(err)? = None;
    for token in state.transfers.lock().map_err(err)?.values() {
        token.cancel();
    }
    Ok(())
}
#[tauri::command]
async fn remote_list(state: State<'_, AppState>, path: String) -> Result<Listing, String> {
    let remote = remote(&state)?;
    let response = remote
        .request(reqwest::Method::GET, "v1/files")?
        .query(&[("path", path)])
        .timeout(Duration::from_secs(15))
        .send()
        .await
        .map_err(err)?;
    checked(response).await?.json().await.map_err(err)
}
#[tauri::command]
async fn remote_action(
    state: State<'_, AppState>,
    action: String,
    path: String,
    name: Option<String>,
) -> Result<(), String> {
    if !["mkdir", "rename", "delete"].contains(&action.as_str()) {
        return Err("Operação inválida.".into());
    }
    if let Some(ref name) = name {
        valid_name(name)?;
    }
    let remote = remote(&state)?;
    let response = remote
        .request(reqwest::Method::POST, "v1/actions")?
        .json(&serde_json::json!({"action": action, "path": path, "name": name}))
        .timeout(Duration::from_secs(30))
        .send()
        .await
        .map_err(err)?;
    checked(response).await?;
    Ok(())
}
#[tauri::command]
fn cancel_transfer(state: State<'_, AppState>, id: String) -> Result<(), String> {
    if let Some(token) = state.transfers.lock().map_err(err)?.get(&id) {
        token.cancel();
    }
    Ok(())
}
#[tauri::command]
async fn transfer_file(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    direction: String,
    source: String,
    destination: String,
) -> Result<(), String> {
    let remote = remote(&state)?;
    let cancel = CancellationToken::new();
    {
        let mut map = state.transfers.lock().map_err(err)?;
        if updater::INSTALLING.load(std::sync::atomic::Ordering::SeqCst) { return Err("A instalação da atualização está iniciando.".into()); }
        if map.contains_key(&id) {
            return Err("Transferência já iniciada.".into());
        }
        map.insert(id.clone(), cancel.clone());
    }
    let result = tokio::select! {
        _ = cancel.cancelled() => Err("Transferência cancelada.".into()),
        result = transfer(move |progress| { let _ = app.emit("transfer-progress", progress); }, &remote, &id, &direction, &source, &destination) => result,
    };
    state.transfers.lock().map_err(err)?.remove(&id);
    result
}
async fn transfer<F: Fn(Progress) + Clone + Send + Sync + 'static>(
    emit: F,
    remote: &Remote,
    id: &str,
    direction: &str,
    source: &str,
    destination: &str,
) -> Result<(), String> {
    if direction == "upload" {
        let file = tokio::fs::File::open(source).await.map_err(err)?;
        let meta = file.metadata().await.map_err(err)?;
        if !meta.is_file() {
            return Err("Selecione arquivos. O envio de pastas será adicionado depois.".into());
        }
        let name = Path::new(source)
            .file_name()
            .ok_or("Nome de arquivo inválido.")?
            .to_string_lossy()
            .to_string();
        valid_name(&name)?;
        let path = format!("{}/{}", destination.trim_end_matches('/'), name);
        let total = meta.len();
        let sent = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let counter = sent.clone();
        let emit = emit.clone();
        let id = id.to_string();
        let mut last = std::time::Instant::now();
        let stream = ReaderStream::with_capacity(file, 128 * 1024).map(move |chunk| {
            if let Ok(ref bytes) = chunk {
                let transferred = counter
                    .fetch_add(bytes.len() as u64, std::sync::atomic::Ordering::Relaxed)
                    + bytes.len() as u64;
                if last.elapsed() >= Duration::from_millis(100) || transferred == total {
                    emit(Progress {
                        id: id.clone(),
                        transferred,
                        total,
                    });
                    last = std::time::Instant::now();
                }
            }
            chunk
        });
        let response = remote
            .request(reqwest::Method::PUT, "v1/content")?
            .query(&[("path", path), ("overwrite", "false".into())])
            .header("Content-Type", "application/octet-stream")
            .header("Content-Length", total)
            .body(reqwest::Body::wrap_stream(stream))
            .send()
            .await
            .map_err(err)?;
        checked(response).await?;
    } else if direction == "download" {
        let name = source.rsplit('/').next().ok_or("Nome inválido.")?;
        valid_name(name)?;
        let target = Path::new(destination).join(name);
        if target.exists() {
            return Err("Já existe um arquivo com esse nome no PC.".into());
        }
        let response = remote
            .request(reqwest::Method::GET, "v1/content")?
            .query(&[("path", source)])
            .send()
            .await
            .map_err(err)?;
        let response = checked(response).await?;
        let total = response.content_length().unwrap_or(0);
        let temp = tempfile::NamedTempFile::new_in(destination).map_err(err)?;
        let mut file = tokio::fs::File::from_std(temp.as_file().try_clone().map_err(err)?);
        let mut stream = response.bytes_stream();
        let mut transferred = 0;
        let mut last = std::time::Instant::now();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(err)?;
            file.write_all(&chunk).await.map_err(err)?;
            transferred += chunk.len() as u64;
            if last.elapsed() >= Duration::from_millis(100) || transferred == total {
                emit(Progress {
                    id: id.into(),
                    transferred,
                    total,
                });
                last = std::time::Instant::now();
            }
        }
        if total > 0 && transferred != total {
            return Err("O arquivo chegou incompleto. Tente novamente.".into());
        }
        file.flush().await.map_err(err)?;
        file.sync_all().await.map_err(err)?;
        drop(file);
        temp.persist_noclobber(target).map_err(err)?;
    } else {
        return Err("Direção inválida.".into());
    }
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .manage(AppState::default())
        .manage(updater::Updater::default())
        .invoke_handler(tauri::generate_handler![
            updater::check_for_updates,
            updater::download_update,
            updater::cancel_update,
            updater::install_update,
            shortcuts,
            local_list,
            local_mkdir,
            local_rename,
            local_trash,
            local_open,
            read_pairing,
            connect_device,
            pair_device,
            remembered_devices,
            connect_remembered,
            forget_remembered,
            connection_health,
            disconnect_device,
            remote_list,
            remote_action,
            transfer_file,
            cancel_transfer
        ])
        .run(tauri::generate_context!())
        .expect("Falha ao iniciar o aplicativo");
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::AsyncReadExt;
    #[tokio::test]
    #[ignore = "Requires Android approval with Remember device selected"]
    async fn android_remember_pairing() {
        let document = pair_connection_client(
            std::env::var("WIFI_ANDROID_ADDRESS").unwrap_or("127.0.0.1:8443".into()),
            std::env::var("WIFI_ANDROID_CODE").unwrap(),
            Some("e".repeat(64)),
            |verification| println!("Verificação: {verification}"),
        ).await.unwrap();
        assert!(document.remembered);
        let output = std::env::var("WIFI_PAIRED_OUTPUT").unwrap();
        std::fs::write(output, serde_json::to_string(&document).unwrap()).unwrap();
    }
    #[tokio::test]
    #[ignore = "Requires an active Android session, WIFI_ANDROID_CODE and approval on Android"]
    async fn android_ip_code_pairing() {
        let code = std::env::var("WIFI_ANDROID_CODE").unwrap();
        let address = std::env::var("WIFI_ANDROID_ADDRESS").unwrap_or("127.0.0.1:18443".into());
        let document = pair_connection(address, code, |verification| {
            println!("Confira no Android: {verification}")
        })
        .await
        .unwrap();
        if let Ok(path) = std::env::var("WIFI_PAIRED_OUTPUT") {
            std::fs::write(path, serde_json::to_string(&document).unwrap()).unwrap();
        }
        let remote = Remote {
            client: reqwest::Client::builder()
                .tls_built_in_root_certs(false)
                .add_root_certificate(
                    reqwest::Certificate::from_pem(document.certificate_pem.as_bytes()).unwrap(),
                )
                .build()
                .unwrap(),
            base: reqwest::Url::parse(&document.address).unwrap(),
            token: document.token,
        };
        checked(
            remote
                .request(reqwest::Method::GET, "v1/files")
                .unwrap()
                .query(&[("path", "/")])
                .send()
                .await
                .unwrap(),
        )
        .await
        .unwrap();
    }
    #[tokio::test]
    #[ignore = "Requires an active Android session and WIFI_ANDROID_PAIRING pointing to its connection JSON"]
    async fn android_real_transfer_round_trip() {
        let text = std::fs::read_to_string(std::env::var("WIFI_ANDROID_PAIRING").unwrap()).unwrap();
        let pairing = parse_pairing(text.trim_start_matches('\u{feff}')).unwrap();
        if let Ok(qr_path) = std::env::var("WIFI_ANDROID_QR") {
            let qr = read_pairing(qr_path).await.unwrap();
            assert_eq!(qr.token, pairing.token);
            assert_eq!(qr.certificate_pem, pairing.certificate_pem);
        }
        let remote = Remote {
            client: reqwest::Client::builder()
                .add_root_certificate(
                    reqwest::Certificate::from_pem(pairing.certificate_pem.as_bytes()).unwrap(),
                )
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .unwrap(),
            base: reqwest::Url::parse(&pairing.address).unwrap(),
            token: pairing.token,
        };
        checked(
            remote
                .request(reqwest::Method::GET, "v1/device")
                .unwrap()
                .send()
                .await
                .unwrap(),
        )
        .await
        .unwrap();
        let temp = tempfile::tempdir().unwrap();
        let name = format!("WiFi-Rust-Test-{}", std::process::id());
        let path = format!("/Music/{name}");
        checked(
            remote
                .request(reqwest::Method::POST, "v1/actions")
                .unwrap()
                .json(&serde_json::json!({"action":"mkdir","path":"/Music","name":name}))
                .send()
                .await
                .unwrap(),
        )
        .await
        .unwrap();
        let bytes: Vec<u8> = (0..2_097_152).map(|i| (i % 251) as u8).collect();
        let source = temp.path().join("Faixa de teste.bin");
        std::fs::write(&source, &bytes).unwrap();
        let received = temp.path().join("received");
        std::fs::create_dir(&received).unwrap();
        let result = async {
            transfer(
                |_| {},
                &remote,
                "android-upload",
                "upload",
                source.to_str().unwrap(),
                &path,
            )
            .await?;
            transfer(
                |_| {},
                &remote,
                "android-download",
                "download",
                &format!("{path}/Faixa de teste.bin"),
                received.to_str().unwrap(),
            )
            .await?;
            assert_eq!(
                std::fs::read(received.join("Faixa de teste.bin")).unwrap(),
                bytes
            );
            assert!(transfer(
                |_| {},
                &remote,
                "android-conflict",
                "upload",
                source.to_str().unwrap(),
                &path
            )
            .await
            .is_err());
            Ok::<(), String>(())
        }
        .await;
        checked(
            remote
                .request(reqwest::Method::POST, "v1/actions")
                .unwrap()
                .json(&serde_json::json!({"action":"delete","path":path}))
                .send()
                .await
                .unwrap(),
        )
        .await
        .unwrap();
        result.unwrap();
    }
    async fn test_server(
        status: &str,
        body: Vec<u8>,
        advertised_length: Option<usize>,
    ) -> (Remote, tokio::task::JoinHandle<Vec<u8>>) {
        use tokio_rustls::rustls::{self, pki_types::PrivatePkcs8KeyDer};
        let generated = rcgen::generate_simple_self_signed(vec!["localhost".into()]).unwrap();
        let cert = generated.cert.der().clone();
        let key = PrivatePkcs8KeyDer::from(generated.signing_key.serialize_der());
        let config = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key.into())
            .unwrap();
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(config));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let status = status.to_string();
        let task = tokio::spawn(async move {
            let (socket, _) = listener.accept().await.unwrap();
            let mut stream = acceptor.accept(socket).await.unwrap();
            let mut request = Vec::new();
            let mut buffer = [0u8; 4096];
            let head_end;
            loop {
                let count = stream.read(&mut buffer).await.unwrap();
                assert!(count > 0);
                request.extend_from_slice(&buffer[..count]);
                if let Some(pos) = request.windows(4).position(|p| p == b"\r\n\r\n") {
                    head_end = pos + 4;
                    break;
                }
            }
            let header = String::from_utf8_lossy(&request[..head_end]).to_lowercase();
            let length: usize = header
                .lines()
                .find_map(|line| {
                    line.strip_prefix("content-length:")
                        .map(|v| v.trim().parse().unwrap())
                })
                .unwrap_or(0);
            while request.len() < head_end + length {
                let count = stream.read(&mut buffer).await.unwrap();
                assert!(count > 0);
                request.extend_from_slice(&buffer[..count]);
            }
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                advertised_length.unwrap_or(body.len())
            );
            stream.write_all(response.as_bytes()).await.unwrap();
            stream.write_all(&body).await.unwrap();
            stream.shutdown().await.unwrap();
            request
        });
        let client = reqwest::Client::builder()
            .add_root_certificate(reqwest::Certificate::from_der(cert.as_ref()).unwrap())
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap();
        (
            Remote {
                client,
                base: reqwest::Url::parse(&format!("https://localhost:{port}/")).unwrap(),
                token: "test-only-token".into(),
            },
            task,
        )
    }
    #[test]
    fn names_cannot_escape_directory_or_use_windows_devices() {
        for name in [
            "../escape",
            "..",
            "a\\b",
            "CON.mp3",
            "LPT1",
            "a:",
            "nome.",
            " final",
            "a\n",
        ] {
            assert!(valid_name(name).is_err(), "{name}");
        }
        for name in ["Minha música.mp3", "Álbuns", "COM10.txt"] {
            assert!(valid_name(name).is_ok());
        }
    }
    #[test]
    fn listing_sorts_folders_first_and_keeps_parent() {
        let temp = tempfile::tempdir().unwrap();
        std::fs::write(temp.path().join("a.mp3"), b"music").unwrap();
        std::fs::create_dir(temp.path().join("Z pasta")).unwrap();
        let listing = read_listing(temp.path().to_str().unwrap()).unwrap();
        assert!(listing.entries[0].is_dir);
        assert_eq!(listing.entries[1].size, 5);
        assert!(listing.parent.is_some());
    }
    #[tokio::test]
    async fn rename_does_not_replace_existing_file() {
        let folder = tempfile::tempdir().unwrap();
        let source = folder.path().join("source.txt");
        let target = folder.path().join("target.txt");
        std::fs::write(&source, b"source").unwrap();
        std::fs::write(&target, b"preserve").unwrap();
        assert!(
            local_rename(source.to_string_lossy().into(), "target.txt".into())
                .await
                .is_err()
        );
        assert_eq!(std::fs::read(&target).unwrap(), b"preserve");
        local_rename(source.to_string_lossy().into(), "renamed.txt".into())
            .await
            .unwrap();
        assert_eq!(
            std::fs::read(folder.path().join("renamed.txt")).unwrap(),
            b"source"
        );
    }
    #[tokio::test]
    async fn upload_streams_authenticated_bytes_and_preserves_conflicts() {
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("Faixa.mp3");
        std::fs::write(&path, b"test music bytes").unwrap();
        let (remote, server) = test_server("201 Created", vec![], None).await;
        transfer(
            |_| {},
            &remote,
            "upload-test",
            "upload",
            path.to_str().unwrap(),
            "/Music",
        )
        .await
        .unwrap();
        let request = server.await.unwrap();
        let header = String::from_utf8_lossy(&request).to_lowercase();
        assert!(header.starts_with("put /v1/content?"));
        assert!(header.contains("overwrite=false"));
        assert!(header.contains("authorization: bearer test-only-token"));
        assert!(request.ends_with(b"test music bytes"));
        let (remote, server) = test_server("409 Conflict", vec![], None).await;
        assert!(transfer(
            |_| {},
            &remote,
            "conflict",
            "upload",
            path.to_str().unwrap(),
            "/Music"
        )
        .await
        .unwrap_err()
        .contains("Já existe"));
        server.await.unwrap();
    }
    #[tokio::test]
    async fn download_publishes_complete_file_and_refuses_overwrite() {
        let folder = tempfile::tempdir().unwrap();
        let (remote, server) = test_server("200 OK", b"audio bytes".to_vec(), None).await;
        transfer(
            |_| {},
            &remote,
            "download-test",
            "download",
            "/Music/Faixa.mp3",
            folder.path().to_str().unwrap(),
        )
        .await
        .unwrap();
        server.await.unwrap();
        assert_eq!(
            std::fs::read(folder.path().join("Faixa.mp3")).unwrap(),
            b"audio bytes"
        );
        assert!(transfer(
            |_| {},
            &remote,
            "overwrite-test",
            "download",
            "/Music/Faixa.mp3",
            folder.path().to_str().unwrap()
        )
        .await
        .unwrap_err()
        .contains("Já existe"));
        assert_eq!(std::fs::read_dir(folder.path()).unwrap().count(), 1);
    }
    #[tokio::test]
    async fn truncated_download_leaves_no_partial_file() {
        let folder = tempfile::tempdir().unwrap();
        let (remote, server) = test_server("200 OK", b"short".to_vec(), Some(100)).await;
        assert!(transfer(
            |_| {},
            &remote,
            "truncated-test",
            "download",
            "/Music/Faixa.mp3",
            folder.path().to_str().unwrap()
        )
        .await
        .is_err());
        server.await.unwrap();
        assert_eq!(std::fs::read_dir(folder.path()).unwrap().count(), 0);
    }
}
