use crate::{checked, err, remote, AppState};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures_util::StreamExt;
use std::{io::Cursor, sync::Arc, time::Duration};
use tauri::State;
use tokio::sync::Semaphore;

const MAX_BYTES: usize = 24 * 1024 * 1024;
pub struct PreviewJobs(Arc<Semaphore>);
impl Default for PreviewJobs { fn default() -> Self { Self(Arc::new(Semaphore::new(2))) } }

#[cfg(test)]
fn thumbnail(bytes: Vec<u8>) -> Result<String, String> { thumbnail_at(bytes, 96) }
fn thumbnail_at(bytes: Vec<u8>, edge: u32) -> Result<String, String> {
    if bytes.len() > MAX_BYTES { return Err("Imagem grande demais para miniatura.".into()); }
    let mut reader = image::ImageReader::new(Cursor::new(bytes)).with_guessed_format().map_err(err)?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(8192); limits.max_image_height = Some(8192); limits.max_alloc = Some(64 * 1024 * 1024);
    reader.limits(limits);
    let image = reader.decode().map_err(err)?.thumbnail(edge, edge);
    let mut output = Cursor::new(Vec::new());
    image.write_to(&mut output, image::ImageFormat::Png).map_err(err)?;
    Ok(format!("data:image/png;base64,{}", STANDARD.encode(output.into_inner())))
}

#[tauri::command]
pub async fn image_thumbnail(state: State<'_, AppState>, jobs: State<'_, PreviewJobs>, side: String, path: String, large: Option<bool>) -> Result<String, String> {
    let _permit = jobs.0.clone().acquire_owned().await.map_err(err)?;
    let extension = std::path::Path::new(&path).extension().and_then(|s| s.to_str()).unwrap_or("").to_ascii_lowercase();
    if !["jpg", "jpeg", "png", "webp", "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac"].contains(&extension.as_str()) { return Err("Formato sem miniatura.".into()); }
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
    let edge = if large == Some(true) { 512 } else { 96 };
    // Only the small decoded preview is returned. Nothing is cached on disk.
    tokio::task::spawn_blocking(move || {
        if ["jpg", "jpeg", "png", "webp"].contains(&extension.as_str()) { thumbnail_at(bytes, edge) } else { audio_cover_at(Cursor::new(bytes), edge) }
    }).await.map_err(err)?
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn embedded_audio_picture_is_decoded() {
        use lofty::{tag::{Tag, TagType}, picture::{Picture, PictureType, MimeType}, prelude::TagExt, config::WriteOptions};
        let file = tempfile::Builder::new().suffix(".wav").tempfile().unwrap();
        let mut wav = Vec::new();
        wav.extend_from_slice(b"RIFF"); wav.extend_from_slice(&68u32.to_le_bytes()); wav.extend_from_slice(b"WAVEfmt "); wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes()); wav.extend_from_slice(&1u16.to_le_bytes()); wav.extend_from_slice(&8000u32.to_le_bytes()); wav.extend_from_slice(&16000u32.to_le_bytes()); wav.extend_from_slice(&2u16.to_le_bytes()); wav.extend_from_slice(&16u16.to_le_bytes()); wav.extend_from_slice(b"data"); wav.extend_from_slice(&32u32.to_le_bytes()); wav.extend_from_slice(&[0;32]);
        std::fs::write(file.path(), wav).unwrap();
        let mut png = Cursor::new(Vec::new()); image::DynamicImage::new_rgb8(200,200).write_to(&mut png,image::ImageFormat::Png).unwrap();
        let mut tag = Tag::new(TagType::Id3v2); tag.push_picture(Picture::new_unchecked(PictureType::CoverFront,Some(MimeType::Png),None,png.into_inner()));
        tag.save_to_path(file.path(), WriteOptions::default()).unwrap();
        let cover = audio_cover(std::fs::File::open(file.path()).unwrap()).unwrap();
        assert!(cover.starts_with("data:image/png;base64,"));
        assert!(audio_cover(Cursor::new(vec![0;32])).is_err());
    }
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

#[cfg(test)]
fn audio_cover<R: std::io::Read + std::io::Seek>(reader: R) -> Result<String, String> { audio_cover_at(reader, 96) }
fn audio_cover_at<R: std::io::Read + std::io::Seek>(reader: R, edge: u32) -> Result<String, String> {
    use lofty::{probe::Probe, file::TaggedFileExt};
    let tagged = Probe::new(reader).guess_file_type().map_err(err)?.read().map_err(err)?;
    let picture = tagged.tags().iter().flat_map(|tag| tag.pictures()).next().ok_or("Música sem capa incorporada.")?;
    thumbnail_at(picture.data().to_vec(), edge)
}
#[tauri::command]
pub async fn prepare_media(app: tauri::AppHandle, path: String) -> Result<String, String> {
    use tauri::Manager;
    let canonical = std::fs::canonicalize(&path).map_err(err)?;
    let extension = canonical.extension().and_then(|s| s.to_str()).unwrap_or("").to_ascii_lowercase();
    if !["jpg", "jpeg", "png", "webp", "gif", "bmp", "mp3", "wav", "flac", "m4a", "ogg", "aac", "opus", "mp4", "webm", "m4v", "mov"].contains(&extension.as_str()) || !canonical.is_file() { return Err("Formato não disponível no visualizador.".into()); }
    app.asset_protocol_scope().allow_file(&canonical).map_err(err)?;
    Ok(canonical.to_string_lossy().into_owned())
}

#[derive(serde::Serialize)]
pub struct AudioMetadata { title: Option<String>, artist: Option<String> }
#[tauri::command]
pub async fn audio_metadata(path: String) -> Result<AudioMetadata, String> {
    tokio::task::spawn_blocking(move || {
        use lofty::{file::TaggedFileExt, prelude::Accessor};
        let tagged = lofty::probe::Probe::open(path).map_err(err)?.read().map_err(err)?;
        let tag = tagged.primary_tag().or_else(|| tagged.first_tag());
        Ok(AudioMetadata { title: tag.and_then(|t| t.title()).map(|v| v.into_owned()), artist: tag.and_then(|t| t.artist()).map(|v| v.into_owned()) })
    }).await.map_err(err)?
}
