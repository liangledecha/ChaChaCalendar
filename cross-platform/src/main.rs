#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod model;
mod store;
mod sync;
mod sync_engine;
mod webdav;

slint::include_modules!();

fn main() -> Result<(), slint::PlatformError> {
    let ui = AppWindow::new()?;
    ui.set_status_text("离线可用 · 未同步".into());

    let weak = ui.as_weak();
    ui.on_sync_requested(move || {
        if let Some(ui) = weak.upgrade() {
            ui.set_status_text("正在同步…".into());
        }
        let weak = weak.clone();
        std::thread::spawn(move || {
            let result = sync_engine::config_from_env().and_then(sync_engine::sync_once);
            let message = match result {
                Ok(message) => message,
                Err(error) => format!("同步失败：{error}"),
            };
            let _ = slint::invoke_from_event_loop(move || {
                if let Some(ui) = weak.upgrade() {
                    ui.set_status_text(message.into());
                }
            });
        });
    });

    ui.run()
}
