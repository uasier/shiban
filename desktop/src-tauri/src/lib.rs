#[tauri::command]
fn read_clipboard() -> String {
    arboard::Clipboard::new()
        .and_then(|mut clipboard| clipboard.get_text())
        .unwrap_or_default()
}

#[tauri::command]
fn open_url(url: String) -> Result<(), String> {
    let allowed = url.starts_with("https://github.com/") || url.starts_with("https://shiban.uasier.com/");
    if !allowed {
        return Err("不允许打开这个地址".into());
    }
    let result = if cfg!(target_os = "macos") {
        std::process::Command::new("open").arg(&url).spawn()
    } else if cfg!(target_os = "windows") {
        std::process::Command::new("cmd").args(["/C", "start", "", &url]).spawn()
    } else {
        std::process::Command::new("xdg-open").arg(&url).spawn()
    };
    result.map(|_| ()).map_err(|error| error.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![read_clipboard, open_url])
        .run(tauri::generate_context!())
        .expect("识办桌面端启动失败");
}
