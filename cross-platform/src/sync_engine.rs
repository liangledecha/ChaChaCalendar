use crate::store::Store;
use crate::sync::merge;
use crate::webdav::{PullResult, WebDav, WebDavConfig};

pub fn sync_once(config: WebDavConfig) -> Result<String, String> {
    let mut store = Store::open_default().map_err(|e| e.to_string())?;
    let device_id = store.device_id().map_err(|e| e.to_string())?;
    let local = store.load_snapshot(&device_id).map_err(|e| e.to_string())?;
    let cached_etag = store.meta("webdav_etag").map_err(|e| e.to_string())?;

    let remote = WebDav::new(config);
    match remote.pull(cached_etag.as_deref())? {
        PullResult::NotModified => Ok("云端无变化".into()),
        PullResult::Missing => {
            let new_etag = remote.push(&local, None)?;
            if let Some(etag) = new_etag {
                store.set_meta("webdav_etag", &etag).map_err(|e| e.to_string())?;
            }
            Ok("已创建云端同步文件".into())
        }
        PullResult::Snapshot { snapshot, etag } => {
            let merged = merge(&local, &snapshot, &device_id);
            store.replace_snapshot(&merged).map_err(|e| e.to_string())?;

            if merged.events == snapshot.events {
                if let Some(etag) = etag {
                    store.set_meta("webdav_etag", &etag).map_err(|e| e.to_string())?;
                }
                return Ok("已拉取云端变化".into());
            }

            let new_etag = remote.push(&merged, etag.as_deref())?;
            if let Some(etag) = new_etag.or(etag) {
                store.set_meta("webdav_etag", &etag).map_err(|e| e.to_string())?;
            }
            Ok("同步完成".into())
        }
    }
}

pub fn config_from_env() -> Result<WebDavConfig, String> {
    let url = std::env::var("CHACHA_WEBDAV_URL")
        .map_err(|_| "未配置 CHACHA_WEBDAV_URL".to_string())?;
    if !url.to_ascii_lowercase().starts_with("https://") {
        return Err("云同步地址必须使用 HTTPS".into());
    }
    Ok(WebDavConfig {
        url,
        username: std::env::var("CHACHA_WEBDAV_USER").unwrap_or_default(),
        password: std::env::var("CHACHA_WEBDAV_PASSWORD").unwrap_or_default(),
    })
}
