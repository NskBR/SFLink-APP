use super::*;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use tauri::Manager;

static STORE_LOCK: Mutex<()> = Mutex::new(());
#[derive(Default, Serialize, Deserialize)]
struct Store {
    client_id: String,
    devices: Vec<Saved>,
}
#[derive(Clone, Serialize, Deserialize)]
struct Saved {
    id: String,
    name: String,
    address: String,
    token: String,
    certificate: String,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct KnownDevice {
    id: String,
    name: String,
    address: Option<String>,
}

#[cfg(windows)]
fn protect(bytes: &[u8], encrypt: bool) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::{Foundation::LocalFree, Security::Cryptography::*};
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len().try_into().map_err(err)?,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    unsafe {
        let ok = if encrypt {
            CryptProtectData(
                &input,
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptUnprotectData(
                &input,
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                std::ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        };
        if ok == 0 {
            return Err(
                "Não foi possível acessar os dispositivos salvos nesta conta Windows.".into(),
            );
        }
        let result = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as *mut _);
        Ok(result)
    }
}
#[cfg(not(windows))]
fn protect(_: &[u8], _: bool) -> Result<Vec<u8>, String> {
    Err("A conexão lembrada nesta versão requer Windows.".into())
}
fn file(app: &AppHandle) -> Result<PathBuf, String> {
    Ok(app
        .path()
        .app_local_data_dir()
        .map_err(err)?
        .join("remembered.dat"))
}
fn load(path: &Path) -> Result<Store, String> {
    if !path.exists() {
        return Ok(Store::default());
    }
    if std::fs::metadata(path).map_err(err)?.len() > 512 * 1024 {
        return Err("Arquivo de dispositivos inválido.".into());
    }
    serde_json::from_slice(&protect(&std::fs::read(path).map_err(err)?, false)?).map_err(err)
}
fn save(path: &Path, store: &Store) -> Result<(), String> {
    let directory = path.parent().ok_or("Pasta de configurações inválida.")?;
    std::fs::create_dir_all(directory).map_err(err)?;
    let bytes = protect(&serde_json::to_vec(store).map_err(err)?, true)?;
    use std::io::Write;
    let mut temporary = tempfile::NamedTempFile::new_in(directory).map_err(err)?;
    temporary.write_all(&bytes).map_err(err)?;
    temporary.as_file().sync_all().map_err(err)?;
    temporary.persist(path).map_err(err)?;
    Ok(())
}
pub(super) fn client_id(app: &AppHandle) -> Result<String, String> {
    let _lock = STORE_LOCK.lock().map_err(err)?;
    let path = file(app)?;
    let mut store = load(&path)?;
    if store.client_id.is_empty() {
        let mut bytes = [0u8; 32];
        getrandom::fill(&mut bytes).map_err(err)?;
        store.client_id = bytes.iter().map(|b| format!("{b:02x}")).collect();
        save(&path, &store)?;
    }
    Ok(store.client_id)
}
pub(super) fn remember(
    app: &AppHandle,
    document: &PairingDocument,
    device: &Device,
) -> Result<(), String> {
    use sha2::{Digest, Sha256};
    let id: String = Sha256::digest(document.certificate_pem.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    let _lock = STORE_LOCK.lock().map_err(err)?;
    let path = file(app)?;
    let mut store = load(&path)?;
    store.devices.retain(|d| d.id != id);
    if store.devices.len() >= 20 {
        return Err("Limite de celulares salvos. Esqueça um dispositivo primeiro.".into());
    }
    store.devices.push(Saved {
        id,
        name: device.name.clone(),
        address: document.address.clone(),
        token: document.token.clone(),
        certificate: document.certificate_pem.clone(),
    });
    save(&path, &store)
}
fn local_ip(ip: Ipv4Addr) -> bool {
    ip.is_private() || ip.is_loopback() || ip.is_link_local()
}
fn base(address: &str) -> Result<reqwest::Url, String> {
    let url = reqwest::Url::parse(address).map_err(err)?;
    let ip: Ipv4Addr = url
        .host_str()
        .ok_or("Endereço inválido.")?
        .parse()
        .map_err(err)?;
    if url.scheme() != "https"
        || !local_ip(ip)
        || url.port() != Some(8443)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err("Endereço local inválido.".into());
    }
    Ok(url)
}
fn candidate(saved: &Saved, address: &str) -> Result<Remote, String> {
    Ok(Remote {
        base: base(address)?,
        token: saved.token.clone(),
        client: reqwest::Client::builder()
            .no_proxy()
            .tls_built_in_root_certs(false)
            .add_root_certificate(
                reqwest::Certificate::from_pem(saved.certificate.as_bytes()).map_err(err)?,
            )
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(2))
            .build()
            .map_err(err)?,
    })
}
fn discover(previous: Vec<String>) -> Result<HashMap<String, Vec<String>>, String> {
    let socket = UdpSocket::bind("0.0.0.0:0").map_err(err)?;
    socket.set_broadcast(true).map_err(err)?;
    socket
        .set_read_timeout(Some(Duration::from_millis(150)))
        .map_err(err)?;
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(err)?;
    let nonce: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    let query =
        serde_json::to_vec(&serde_json::json!({"type":"wifi-files-discover-v1", "nonce":nonce}))
            .map_err(err)?;
    socket
        .send_to(&query, "255.255.255.255:8445")
        .map_err(err)?;
    // Also ask the previous address directly; useful when broadcast is filtered by a router/VPN.
    for address in previous {
        if let Ok(url) = base(&address) {
            if let Some(host) = url.host_str() {
                let _ = socket.send_to(&query, format!("{host}:8445"));
            }
        }
    }
    let start = std::time::Instant::now();
    let mut results = HashMap::<String, Vec<String>>::new();
    let mut buffer = [0u8; 2048];
    while start.elapsed() < Duration::from_millis(900) {
        if let Ok((length, SocketAddr::V4(peer))) = socket.recv_from(&mut buffer) {
            if !local_ip(*peer.ip()) {
                continue;
            }
            let Ok(value) = serde_json::from_slice::<serde_json::Value>(&buffer[..length]) else {
                continue;
            };
            let id = value["id"].as_str().unwrap_or("");
            if value["type"] != "wifi-files-device-v1"
                || value["nonce"] != nonce
                || value["port"] != 8443
                || id.len() != 64
                || !id.bytes().all(|b| b.is_ascii_hexdigit())
            {
                continue;
            }
            let addresses = results.entry(id.to_string()).or_default();
            let address = format!("https://{}:8443/", peer.ip());
            if !addresses.contains(&address) && addresses.len() < 8 {
                addresses.push(address);
            }
            if results.len() >= 64 {
                break;
            }
        }
    }
    Ok(results)
}
async fn probe(remote: &Remote) -> Result<Device, String> {
    checked(
        remote
            .request(reqwest::Method::GET, "v1/device")?
            .timeout(Duration::from_secs(2))
            .send()
            .await
            .map_err(err)?,
    )
    .await?
    .json()
    .await
    .map_err(err)
}
#[tauri::command]
pub(super) async fn remembered_devices(app: AppHandle) -> Result<Vec<KnownDevice>, String> {
    let devices = {
        let _lock = STORE_LOCK.lock().map_err(err)?;
        load(&file(&app)?)?.devices
    };
    if devices.is_empty() {
        return Ok(vec![]);
    }
    let previous = devices.iter().map(|d| d.address.clone()).collect();
    let discovered = tauri::async_runtime::spawn_blocking(move || discover(previous))
        .await
        .map_err(err)?
        .unwrap_or_default();
    let mut tasks = futures_util::stream::iter(devices.into_iter().map(|saved| {
        let mut addresses = discovered.get(&saved.id).cloned().unwrap_or_default();
        if !addresses.contains(&saved.address) {
            addresses.push(saved.address.clone());
        }
        async move {
            let mut available = None;
            for address in addresses {
                if let Ok(remote) = candidate(&saved, &address) {
                    if probe(&remote).await.is_ok() {
                        available = Some(address);
                        break;
                    }
                }
            }
            KnownDevice {
                id: saved.id,
                name: saved.name,
                address: available,
            }
        }
    }))
    .buffer_unordered(4);
    let mut result = vec![];
    while let Some(device) = tasks.next().await {
        result.push(device);
    }
    Ok(result)
}
#[tauri::command]
pub(super) async fn connect_remembered(
    app: AppHandle,
    state: State<'_, AppState>,
    id: String,
    address: String,
) -> Result<Device, String> {
    let saved = {
        let _lock = STORE_LOCK.lock().map_err(err)?;
        load(&file(&app)?)?
            .devices
            .into_iter()
            .find(|d| d.id == id)
            .ok_or("Dispositivo não lembrado.")?
    };
    let remote = candidate(&saved, &address)?;
    let device = probe(&remote).await.map_err(|_| "Não foi possível reconectar. Abra o Android na mesma rede; se o acesso foi revogado, pareie novamente.".to_string())?;
    {
        let _lock = STORE_LOCK.lock().map_err(err)?;
        let path = file(&app)?;
        let mut store = load(&path)?;
        if let Some(saved) = store.devices.iter_mut().find(|d| d.id == id) {
            saved.address = address;
            save(&path, &store)?;
        } else {
            return Err("Dispositivo esquecido durante a conexão.".into());
        }
    }
    *state.remote.lock().map_err(err)? = Some(remote);
    Ok(device)
}
#[tauri::command]
pub(super) fn forget_remembered(app: AppHandle, id: String) -> Result<(), String> {
    let _lock = STORE_LOCK.lock().map_err(err)?;
    let path = file(&app)?;
    let mut store = load(&path)?;
    store.devices.retain(|d| d.id != id);
    save(&path, &store)
}
#[tauri::command]
pub(super) async fn connection_health(state: State<'_, AppState>) -> Result<Device, String> {
    probe(&remote(&state)?).await
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    #[ignore = "Requires the restarted Android and WIFI_ANDROID_PAIRING"]
    async fn android_saved_token_reconnects_after_restart() {
        use sha2::{Digest, Sha256};
        let document = parse_pairing(&std::fs::read_to_string(std::env::var("WIFI_ANDROID_PAIRING").unwrap()).unwrap()).unwrap();
        assert!(document.remembered);
        let id: String = Sha256::digest(document.certificate_pem.as_bytes()).iter().map(|b| format!("{b:02x}")).collect();
        let store = Store { client_id: "e".repeat(64), devices: vec![Saved { id: id.clone(), name: "Android".into(), address: document.address.clone(), token: document.token, certificate: document.certificate_pem }] };
        let directory = tempfile::tempdir().unwrap(); let path = directory.path().join("trusted.dat");
        save(&path, &store).unwrap(); let reopened = load(&path).unwrap(); let saved = &reopened.devices[0];
        let discovered = discover(vec![saved.address.clone()]).unwrap();
        if std::env::var("WIFI_REQUIRE_DISCOVERY").is_ok() { assert!(discovered.contains_key(&id), "Android identity was not discovered"); }
        let remote = candidate(saved, &saved.address).unwrap();
        let device = probe(&remote).await.unwrap(); assert!(!device.name.is_empty());
        checked(remote.request(reqwest::Method::GET, "v1/files").unwrap().query(&[("path", "/")]).send().await.unwrap()).await.unwrap();
        println!("PASS: Windows DPAPI reload, pinned TLS, remembered token and real Android listing after restart");
    }
    #[test]
    fn credentials_are_encrypted_and_authenticated() {
        let bytes = b"persistent-token-secret";
        let protected = protect(bytes, true).unwrap();
        assert!(!protected.windows(bytes.len()).any(|part| part == bytes));
        assert_eq!(protect(&protected, false).unwrap(), bytes);
        let mut changed = protected;
        let end = changed.len() - 1;
        changed[end] ^= 1;
        assert!(protect(&changed, false).is_err());
    }
    #[test]
    fn saved_address_rejects_public_or_redirect_style_urls() {
        assert!(base("https://192.168.1.12:8443/").is_ok());
        for url in [
            "http://192.168.1.12:8443",
            "https://8.8.8.8:8443",
            "https://user@192.168.1.12:8443",
            "https://192.168.1.12:8443/?secret=x",
        ] {
            assert!(base(url).is_err());
        }
    }
    #[test]
    fn store_survives_reopen_and_removal() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("saved.dat");
        let mut store = Store {
            client_id: "a".repeat(64),
            devices: vec![Saved {
                id: "b".repeat(64),
                name: "Android".into(),
                address: "https://192.168.1.12:8443/".into(),
                token: "secret-token".into(),
                certificate: "certificate".into(),
            }],
        };
        save(&path, &store).unwrap();
        assert_eq!(load(&path).unwrap().devices[0].token, "secret-token");
        store.devices.clear();
        save(&path, &store).unwrap();
        assert!(load(&path).unwrap().devices.is_empty());
    }
}
