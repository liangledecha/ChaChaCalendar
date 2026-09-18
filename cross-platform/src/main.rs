mod model;
mod sync;

slint::include_modules!();

fn main() -> Result<(), slint::PlatformError> {
    let ui = AppWindow::new()?;
    ui.set_status_text("离线可用 · 云同步骨架已就绪".into());
    ui.run()
}
