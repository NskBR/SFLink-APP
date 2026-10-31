use crate::{err, remote, valid_name, AppState};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::BTreeMap, path::{Path, PathBuf}, sync::Mutex};
use tauri::{AppHandle, Manager, State};
static LOCK: Mutex<()> = Mutex::new(());
#[derive(Serialize, Deserialize)]
struct Copy { path: PathBuf, size: u64, modified: Option<u128> }
fn stamp(path: &Path) -> Option<u128> { std::fs::metadata(path).ok()?.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok().map(|v| v.as_nanos()) }
fn readable(path: &Path, size: Option<u64>) -> bool {
    std::fs::File::open(path).ok().and_then(|f| f.metadata().ok()).is_some_and(|m| m.is_file() && size.is_none_or(|size| size == m.len()))
}
fn key(identity: &str, source: &str, size: u64, modified: Option<u64>) -> String {
    format!("{:x}", Sha256::digest(format!("{identity}\0{source}\0{size}\0{modified:?}")))
}
fn load(path: &Path) -> BTreeMap<String, Copy> { std::fs::read(path).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default() }
fn save(index: &Path, values: &BTreeMap<String, Copy>) -> Result<(), String> {
    let parent = index.parent().ok_or("Pasta de downloads inválida.")?;
    std::fs::create_dir_all(parent).map_err(err)?;
    let file = tempfile::NamedTempFile::new_in(parent).map_err(err)?;
    serde_json::to_writer(file.as_file(), values).map_err(err)?;
    file.as_file().sync_all().map_err(err)?;
    file.persist(index).map_err(err)?; Ok(())
}
pub fn remember(app: &AppHandle, identity: &str, source: &str, path: &Path, modified: Option<u64>) -> Result<(), String> {
    let index = app.path().app_local_data_dir().map_err(err)?.join("downloaded-files.json");
    remember_at(&index, identity, source, path, modified)
}
fn remember_at(index: &Path, identity: &str, source: &str, path: &Path, modified: Option<u64>) -> Result<(), String> {
    let _lock = LOCK.lock().map_err(err)?;
    let size = std::fs::metadata(path).map_err(err)?.len();
    let mut values = load(index);
    values.insert(key(identity, source, size, modified), Copy { path: path.to_owned(), size, modified: stamp(path) });
    values.retain(|_, copy| readable(&copy.path, Some(copy.size)));
    save(index, &values)
}
fn find_at(index: &Path, identity: &str, source: &str, destination: &Path, size: u64, modified: Option<u64>) -> Result<Option<String>, String> {
    let _lock = LOCK.lock().map_err(err)?;
    let values = load(index);
    if let Some(copy) = values.get(&key(identity, source, size, modified)) {
        if readable(&copy.path, Some(size)) && stamp(&copy.path) == copy.modified { return Ok(Some(copy.path.to_string_lossy().into_owned())); }
        // Do not adopt an edited or stale known copy just because its name matches.
        return Ok(None);
    }
    let name = source.rsplit('/').next().ok_or("Nome inválido.")?; valid_name(name)?;
    // Adopt downloads from older versions when name and size match the listing.
    let existing = destination.join(name);
    if values.values().any(|copy| copy.path == existing) { return Ok(None); }
    Ok(readable(&existing, Some(size)).then(|| existing.to_string_lossy().into_owned()))
}
#[tauri::command]
pub fn find_download(app: AppHandle, state: State<'_, AppState>, source: String, destination: String, size: u64, modified: Option<u64>) -> Result<Option<String>, String> {
    let device = remote(&state)?;
    let index = app.path().app_local_data_dir().map_err(err)?.join("downloaded-files.json");
    let found = find_at(&index, &device.identity, &source, Path::new(&destination), size, modified)?;
    if let Some(path) = &found { let _ = remember_at(&index, &device.identity, &source, Path::new(path), modified); }
    Ok(found)
}
#[tauri::command]
pub fn local_readable(path: String) -> bool { readable(Path::new(&path), None) }

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn downloads_survive_index_reload_and_deleted_files_are_not_reused() {
        let folder = tempfile::tempdir().unwrap(); let index = folder.path().join("index.json"); let file = folder.path().join("song (1).mp3");
        std::fs::write(&file, b"music").unwrap(); remember_at(&index, "phone-a", "/Music/song.mp3", &file, Some(10)).unwrap();
        assert_eq!(find_at(&index,"phone-a","/Music/song.mp3",folder.path(),5,Some(10)).unwrap(),Some(file.to_string_lossy().into_owned()));
        assert!(find_at(&index,"phone-b","/Music/song.mp3",folder.path(),5,Some(10)).unwrap().is_none());
        assert!(find_at(&index,"phone-a","/Music/song.mp3",folder.path(),6,Some(11)).unwrap().is_none());
        std::fs::remove_file(&file).unwrap(); assert!(find_at(&index,"phone-a","/Music/song.mp3",folder.path(),5,Some(10)).unwrap().is_none());
    }
    #[test] fn older_download_requires_matching_size_and_readable_file() {
        let folder = tempfile::tempdir().unwrap(); let index = folder.path().join("index.json");
        std::fs::write(folder.path().join("photo.jpg"),b"photo").unwrap();
        assert!(find_at(&index,"phone","/photo.jpg",folder.path(),5,None).unwrap().is_some());
        assert!(find_at(&index,"phone","/photo.jpg",folder.path(),6,None).unwrap().is_none());
        assert!(find_at(&index,"phone","/..",folder.path(),5,None).is_err());
        remember_at(&index,"phone","/photo.jpg",&folder.path().join("photo.jpg"),Some(10)).unwrap();
        assert!(find_at(&index,"other-phone","/photo.jpg",folder.path(),5,Some(10)).unwrap().is_none());
        assert!(find_at(&index,"phone","/photo.jpg",folder.path(),5,Some(11)).unwrap().is_none());
    }
}
