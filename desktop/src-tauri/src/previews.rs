use crate::{checked, err, remote, AppState};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures_util::StreamExt;
use std::{io::Cursor, sync::Arc, time::Duration};
use tauri::State;
use tokio::sync::Semaphore;

const MAX_BYTES: usize = 24 * 1024 * 1024;
pub struct PreviewJobs(Arc<Semaphore>);
impl Default for PreviewJobs { fn default() -> Self { Self(Arc::new(Semaphore::new(2))) } }

fn thumbnail(bytes: Vec<u8>) -> Result<String, String> {
    if bytes.len() > MAX_BYTES { return Err("Imagem grande demais para miniatura.".into()); }
    let mut reader = image::ImageReader::new(Cursor::new(bytes)).with_guessed_format().map_err(err)?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192); limits.max_image_height = Some(8192); limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().map_err(err)?.thumbnail(96, 96);
    let mut output = Cursor::new(Vec::new());
    image.write_to(&mut output, image::ImageFormat::Png).map_err(err)?;
    Ok(format!("data:image/png;base64,{}", STANDARD.encode(output.into_inner())))
}

#[tauri::command]
pub async fn image_thumbnail(state: State<'_, AppState>, jobs: State<'_, PreviewJobs>, side: String, path: String) -> Result<String, String> {
    let _permit = jobs.0.clone().acquire_owned().await.map_err(err)?;
    let extension = std::path::Path::new(&path).extension().and_then(|s| s.to_str()).unwrap_or("").to_ascii_lowercase();
    if !["jpg", "jpeg", "png", "webp"].contains(&extension.as_str()) { return Err("Formato sem miniatura.".into()); }
    let bytes = if side == "remote" {
        let remote = remote(&state)?;
        let response = checked(remote.request(reqwest::Method::GET, "v1/content")?.query(&[("path", &path)]).timeout(Duration::from_secs(20)).send().await.map_err(err)?).await?;
        if response.content_length().unwrap_or(0) > MAX_BYTES as u64 { return Err("Imagem grande demais.".into()); }
        let mut stream = response.bytes_stream(); let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await { let chunk = chunk.map_err(err)?; if bytes.len() + chunk.len() > MAX_BYTES { return Err("Imagem grande demais.".into()); } bytes.extend_from_slice(&chunk); }
        bytes
    } else if side == "local" {
        if tokio::fs::metadata(&path).await.map_err(err)?.len() > MAX_BYTES as u64 { return Err("Imagem grande demais.".into()); }
        let file = tokio::fs::File::open(path).await.map_err(err)?;
        use tokio::io::AsyncReadExt;
        let mut bytes = Vec::new(); file.take(MAX_BYTES as u64 + 1).read_to_end(&mut bytes).await.map_err(err)?; bytes
    } else { return Err("Painel inválido.".into()); };
    // Only the small decoded preview is returned. Nothing is cached on disk.
    tokio::task::spawn_blocking(move || thumbnail(bytes)).await.map_err(err)?
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn preview_is_small_valid_png_and_rejects_corrupt_data() {
        let mut bytes = Cursor::new(Vec::new());
        image::DynamicImage::new_rgb8(300, 200).write_to(&mut bytes, image::ImageFormat::Png).unwrap();
        let data = thumbnail(bytes.into_inner()).unwrap();
        let decoded = STANDARD.decode(data.strip_prefix("data:image/png;base64,").unwrap()).unwrap();
        let image = image::load_from_memory(&decoded).unwrap();
        assert_eq!((image.width(), image.height()), (96, 64));
        assert!(thumbnail(vec![0, 1, 2]).is_err());
    }
}
