// ---------------------------------------------------------------------------
// LionBox 桌面版 —— Tauri 2 套壳（系统 WebView）
//
// 【为什么是 Tauri】同一个"壳 + 现有 WebUI"的思路，但**不带自己的浏览器**：
// 界面用系统自带的 WebView2 渲染（Windows 10/11 都有），所以安装包只有几 MB，
// 而不是 Electron 那版的 178.79 MB —— 差的 170 多 MB 全是一个被打包进去的 Chromium。
// 客户端一行没重写：窗口里加载的就是后端在 http://127.0.0.1:8080 发的那份 WebUI。
//
// 这个 main.rs 只做三件事：
//   1. 起窗口前先探测本地后端；没起来就用主程序自带的 JRE 把 jar 拉起来（最多等 2 分钟）；
//   2. 窗口直接指向 WebUI（tauri.conf.json 里的 url）；
//   3. 站外链接交给系统浏览器，别在壳里再套别人的网站。
//
// 编译：cd desktop/tauri && npm install && npx tauri build
// ---------------------------------------------------------------------------

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

/// 后端地址。默认用主程序那个 8080（两边一致才好排查），但允许 `LIONBOX_URL` 覆盖 ——
/// 自测、换端口部署、以及"同时开两个实例"都要用得上。
const DEFAULT_URL: &str = "http://127.0.0.1:8080";

fn backend_url() -> String {
    std::env::var("LIONBOX_URL").unwrap_or_else(|_| DEFAULT_URL.to_string())
}

fn host_port(url: &str) -> (String, u16) {
    let rest = url.split("://").nth(1).unwrap_or("127.0.0.1:8080");
    let hostport = rest.split('/').next().unwrap_or("127.0.0.1:8080");
    let mut it = hostport.split(':');
    let host = it.next().unwrap_or("127.0.0.1").to_string();
    let port = it.next().and_then(|p| p.parse::<u16>().ok()).unwrap_or(8080);
    (host, port)
}

/// 端口通不通＝服务起没起（比发 HTTP 请求省事，也不会被 404 之类干扰）
fn port_open(host: &str, port: u16) -> bool {
    match TcpStream::connect_timeout(
        &format!("{}:{}", host, port).parse().unwrap(),
        Duration::from_millis(800),
    ) {
        Ok(mut s) => {
            // 再发一个最小 HTTP 请求确认对面真是 HTTP 服务（不是别的进程占着端口）
            let _ = s.set_read_timeout(Some(Duration::from_millis(800)));
            let req = format!("GET /api/runtime/mode HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n", host);
            if s.write_all(req.as_bytes()).is_err() {
                return false;
            }
            let mut buf = [0u8; 64];
            match s.read(&mut buf) {
                Ok(n) if n > 0 => String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/"),
                _ => false,
            }
        }
        Err(_) => false,
    }
}

/// LionBox 主程序安装目录（桌面版不带后端，用主程序那份 jar + 精简 JRE）
fn installed_dirs() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Ok(local) = std::env::var("LOCALAPPDATA") {
        out.push(Path::new(&local).join("Programs").join("LionBox"));
    }
    if let Ok(pf) = std::env::var("ProgramFiles") {
        out.push(Path::new(&pf).join("LionBox"));
    }
    out
}

fn find_jar() -> Option<(PathBuf, PathBuf)> {
    if let Ok(explicit) = std::env::var("LIONBOX_JAR") {
        let p = PathBuf::from(&explicit);
        if p.is_file() {
            let dir = p.parent().map(|d| d.to_path_buf()).unwrap_or_default();
            return Some((p, dir));
        }
    }
    for dir in installed_dirs() {
        if !dir.is_dir() {
            continue;
        }
        if let Ok(rd) = std::fs::read_dir(&dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if name.starts_with("lion-code-agent-harness") && name.ends_with(".jar") {
                    return Some((e.path(), dir.clone()));
                }
            }
        }
    }
    None
}

fn find_java(install_dir: &Path) -> PathBuf {
    let bundled = install_dir.join("runtime-jre").join("bin").join("java.exe");
    if bundled.is_file() {
        return bundled;
    }
    if let Ok(j) = std::env::var("LIONBOX_JAVA") {
        let p = PathBuf::from(j);
        if p.is_file() {
            return p;
        }
    }
    PathBuf::from("java")
}

/// 后端没起就拉起来；返回 Err(原因) 给界面显示
fn ensure_backend(url: &str) -> Result<(), String> {
    let (host, port) = host_port(url);
    if port_open(&host, port) {
        return Ok(());
    }

    let (jar, dir) = find_jar().ok_or_else(|| {
        "没找到本地服务：请先安装 LionBox（WebUI 版），或把 LIONBOX_JAR 指到后端 jar。".to_string()
    })?;

    let java = find_java(&dir);
    let mut cmd = Command::new(&java);
    cmd.arg("-jar").arg(&jar).current_dir(&dir);
    // 别弹黑窗：桌面版自己就是窗口，后面再冒一个控制台很出戏
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.spawn().map_err(|e| format!("启动本地服务失败：{}", e))?;

    let deadline = Instant::now() + Duration::from_secs(120);
    while Instant::now() < deadline {
        if port_open(&host, port) {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    Err("本地服务启动超时（等了 2 分钟还没起来）。可以到 LionBox 安装目录手动跑 启动LionBox.bat 看看报什么错。".to_string())
}

fn main() {
    // 自检模式：只验"壳自己那部分逻辑"（地址解析、后端探测、jar/java 定位），**不建窗口**。
    // 为什么要留这个：发布脚本要能在没有任何桌面交互的环境里验一遍壳的逻辑；
    // 而 WebView2 宿主在受限沙箱里可能起不来（它和浏览器进程之间走命名管道），
    // 那种失败是环境问题、不该让"壳写没写对"这件事变得没法验。
    if std::env::args().any(|a| a == "--selftest") {
        let out = std::env::args()
            .find_map(|a| a.strip_prefix("--out=").map(|s| s.to_string()));
        std::process::exit(selftest(out));
    }

    tauri::Builder::default()
        .setup(|app| {
            // 窗口不用配置文件里那份，改成代码里建 —— 只为一件事：**显式指定 WebView2 的数据目录**。
            // 默认位置是 %LOCALAPPDATA%\<bundle identifier>\EBWebView，在有些受限环境里
            // 建这个目录会直接失败，表现就是启动即崩、报 "Failed to setup app: 拒绝访问 (os error 5)"。
            // 自己指到 %LOCALAPPDATA%\LionBox\webview2，既避开这个坑，也让卸载时清理更清楚。
            let (data_dir, data_dir_err) = webview_data_dir();
            if let Some(e) = &data_dir_err {
                eprintln!("[LionBox] {}", e);
            }
            let mut builder = tauri::WebviewWindowBuilder::new(
                app,
                "main",
                tauri::WebviewUrl::App("index.html".into()),
            )
            .title("LionBox")
            .inner_size(1280.0, 860.0)
            .min_inner_size(900.0, 600.0)
            .center();
            #[cfg(windows)]
            {
                if let Some(d) = data_dir {
                    builder = builder.data_directory(d);
                }
            }
            if let Err(e) = builder.build() {
                // 【兜底】窗口建不出来（系统 WebView 被策略/杀软挡了、运行时被卸了…）时，
                // 不能让用户对着一个"闪一下就没了"的程序发呆：直接把 WebUI 交给系统浏览器打开，
                // 至少功能是通的，并在控制台说清楚原因。
                eprintln!("[LionBox] 建窗口失败，改用系统浏览器打开 WebUI：{}", e);
                open_in_browser(&backend_url());
                return Ok(());
            }

            // 启动顺序刻意设计成"先给个占位页，再跳真界面"：
            // 窗口先加载包内的 ui/index.html（"正在启动本地服务…"），
            // 然后这个线程去把后端拉起来，就绪了再把窗口导航到真正的 WebUI。
            // 直接让窗口指 WebUI 的话，后端还没起来时会先闪一屏"无法访问此网站"。
            let url = backend_url();
            let handle = app.handle().clone();
            std::thread::spawn(move || {
                let target = url.clone();
                match ensure_backend(&url) {
                    Ok(()) => {
                        if let Some(w) = tauri::Manager::get_webview_window(&handle, "main") {
                            match target.parse() {
                                // 有 navigate 就直接换页面（干净，不留历史）
                                Ok(u) => {
                                    if w.navigate(u).is_err() {
                                        let _ = w.eval(&format!("location.href='{}'", target));
                                    }
                                }
                                Err(_) => {
                                    let _ = w.eval(&format!("location.href='{}'", target));
                                }
                            }
                        }
                    }
                    Err(msg) => {
                        if let Some(w) = tauri::Manager::get_webview_window(&handle, "main") {
                            let js = format!(
                                "document.body.innerHTML='<div style=\"font-family:Microsoft YaHei UI;padding:40px;line-height:1.9;color:#e4e6e8;background:#0d0e10;height:100vh\"><h2>连不上本地服务</h2><p>{}</p><p style=\"color:#a3a8ae\">装好 LionBox（WebUI 版）后重开本窗口即可。</p></div>'",
                                msg.replace('\'', "\\'")
                            );
                            let _ = w.eval(&js);
                        }
                    }
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running LionBox desktop shell");
}

/// WebView2 的缓存/用户数据目录。
/// 返回 (目录, 失败原因)：失败原因要带出来 —— 第一版只回 Option，自检里看到个 null
/// 完全不知道为什么没建成（这次就是：目录不存在 + create_dir_all 失败，但看不出哪个原因）。
fn webview_data_dir() -> (Option<std::path::PathBuf>, Option<String>) {
    // 允许用环境变量指定：自测时把缓存指到可写目录（受限环境里 %LOCALAPPDATA% 可能不让写），
    // 用户装了多份也能各自分开缓存。
    if let Ok(custom) = std::env::var("LIONBOX_WEBVIEW_DATA_DIR") {
        if !custom.trim().is_empty() {
            let dir = std::path::PathBuf::from(&custom);
            return match std::fs::create_dir_all(&dir) {
                Ok(()) => (Some(dir), None),
                Err(e) => (None, Some(format!("建目录 {} 失败: {}", dir.display(), e))),
            };
        }
    }
    let base = match std::env::var("LOCALAPPDATA") {
        Ok(v) => v,
        Err(e) => return (None, Some(format!("读不到 LOCALAPPDATA: {}", e))),
    };
    let dir = std::path::Path::new(&base).join("LionBox").join("webview2");
    match std::fs::create_dir_all(&dir) {
        Ok(()) => (Some(dir), None),
        Err(e) => (None, Some(format!("建目录 {} 失败: {}", dir.display(), e))),
    }
}

/// 用系统默认浏览器打开（窗口建不出来时的兜底）
fn open_in_browser(url: &str) {
    #[cfg(windows)]
    {
        let _ = Command::new("cmd").args(["/c", "start", "", url]).spawn();
    }
    #[cfg(not(windows))]
    {
        let _ = url;
    }
}

/// 自检：不建窗口，只验壳自己的逻辑；结果写进 --out 指定的文件（退出码 0 = 全通）。
///
/// 【为什么写文件而不是只打 stdout】这是一个 windows_subsystem="windows" 的程序，
/// release 版**没有控制台**，println! 出来的东西根本没人接（第一版就是这样：
/// 手动跑看着有输出，脚本里重定向却是空的）。GUI 程序要把结果交出来就老老实实写文件。
fn selftest(out: Option<String>) -> i32 {
    let url = backend_url();
    let (host, port) = host_port(&url);
    let up = port_open(&host, port);
    let jar = find_jar();
    let java = jar.as_ref().map(|(_, dir)| find_java(dir));
    let (data_dir, data_dir_err) = webview_data_dir();

    let jstr = |p: &Option<std::path::PathBuf>| match p {
        Some(x) => format!("\"{}\"", x.display().to_string().replace('\\', "\\\\")),
        None => "null".to_string(),
    };
    // 必须是合法 JSON（发布脚本要解析它）——第一版漏了几个逗号，json.loads 直接报错
    let json = format!(
        "{{\n  \"url\": \"{}\",\n  \"host\": \"{}\",\n  \"port\": {},\n  \"backend_up\": {},\n  \
         \"jar\": {},\n  \"java\": {},\n  \"webview2_data_dir\": {},\n  \
         \"webview2_data_dir_error\": {},\n  \"webview2_runtime\": \"{}\",\n  \"result\": \"{}\"\n}}\n",
        url,
        host,
        port,
        up,
        jstr(&jar.as_ref().map(|(p, _)| p.clone())),
        jstr(&java),
        jstr(&data_dir),
        match &data_dir_err {
            Some(e) => format!("\"{}\"", e.replace('\\', "\\\\").replace('"', "'")),
            None => "null".to_string(),
        },
        webview_runtime_version(),
        if up || jar.is_some() { "OK" } else { "NO_BACKEND" }
    );

    print!("{}", json);
    let _ = std::io::Write::flush(&mut std::io::stdout());
    if let Some(path) = out {
        if let Err(e) = std::fs::write(&path, json.as_bytes()) {
            eprintln!("[LionBox] 自检结果写不进去 {}: {}", path, e);
            return 5;
        }
    }

    // 判定标准：地址解析对了、而且（后端在跑 或 找得到 jar 能拉起来）——任一成立就算这壳能用
    if up || jar.is_some() { 0 } else { 4 }
}

/// 系统 WebView2 运行时版本（拿不到就写 none）——装完能不能显示界面取决于它
fn webview_runtime_version() -> String {
    // 直接查注册表太啰嗦，这里用运行时目录名当版本（Edge WebView2 的 Application\<版本>）
    let base = std::env::var("ProgramFiles(x86)").unwrap_or_else(|_| r"C:\Program Files (x86)".to_string());
    let dir = std::path::Path::new(&base).join("Microsoft").join("EdgeWebView").join("Application");
    if let Ok(rd) = std::fs::read_dir(&dir) {
        let mut best: Option<String> = None;
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
                best = Some(match best {
                    Some(b) if b >= n => b,
                    _ => n,
                });
            }
        }
        if let Some(v) = best {
            return v;
        }
    }
    "none".to_string()
}
