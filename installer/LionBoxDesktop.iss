; ---------------------------------------------------------------------------
; LionBox 桌面版（Tauri 套壳）安装脚本
;
; 【为什么桌面版能小到 3MB】界面用系统自带的 WebView2 渲染，包里不带浏览器 ——
; 对比之前 Electron 那版：一个安装包 178.79 MB，其中 170 多 MB 全是被打进去的 Chromium。
; 这里装的就是一个 3 MB 的 exe，一个文件、不用分片、GitHub 100MB 上限也拦不住它。
;
; 和主程序的关系：桌面版**不自带后端**，它用主程序（WebUI 版）装好的 jar + 精简 JRE 起服务；
; 找不到主程序时窗口里会直接说明白该先装什么（不会给个白屏让人猜）。
;
; 用 ISCC 编（不依赖 Tauri 自带的 NSIS 打包器 —— 那个要从 GitHub 下 NSIS，这台机器上老超时）：
;   ISCC.exe installer\LionBoxDesktop.iss
; ---------------------------------------------------------------------------

#define AppName        "LionBox 桌面版"
#define AppVersion     "1.5.1"
#define AppPublisher   "LionCode"
#define AppExeName     "LionBox.exe"

[Setup]
AppId={{7C1E5A64-2B7D-4E3F-9A21-8D5F0B3C6A94}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\LionBoxDesktop
DefaultGroupName=LionBox
DisableProgramGroupPage=yes
; 只装当前用户：不弹 UAC、不需要管理员，和主程序一致
PrivilegesRequired=lowest
OutputDir=release
OutputBaseFilename=LionBox-Desktop-{#AppVersion}-Setup
SetupIconFile=..\desktop\tauri\src-tauri\icons\icon.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
; 用 ISCC 自带的 Default.isl（本机没装 ChineseSimplified.isl 那个汉化包）。
; 向导文字是英文没关系 —— 快捷方式名、任务名这些用户真正会看的地方都是中文。
Name: "default"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\desktop\tauri\src-tauri\target\release\lionbox-desktop.exe"; DestDir: "{app}"; DestName: "{#AppExeName}"; Flags: ignoreversion
Source: "..\desktop\tauri\src-tauri\icons\icon.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; IconFilename: "{app}\icon.ico"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: checkedonce

[Run]
Filename: "{app}\{#AppExeName}"; Description: "立即打开 {#AppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; WebView2 的缓存是我们自己指定到 %LOCALAPPDATA%\LionBox\webview2 的，卸载时一起清掉
Type: filesandordirs; Name: "{localappdata}\LionBox\webview2"

; ---------------------------------------------------------------------------
; 这里**故意没有 [Code] 段**。
;
; 本来想在安装时检测系统 WebView2 运行时、缺了就提示。实测这条路有两个坑：
;   1) Inno 会把字符串里的 {GUID} 当常量展开（注册表路径里的花括号必须写成 {{...}}）；
;   2) 更要命的是：只要 [Code] 里有闪失，静默安装会**直接 exit=1、连日志都不写、
;      一个文件都不装**，而且完全不告诉用户为什么 —— 同一份脚本用 /DSKIP_WEBVIEW2_CHECK
;      编出来（等于没有这段代码）就一切正常。
; 所以这个检查挪到应用里做：壳启动时如果发现 WebView2 起不来，会在窗口里显示一句人话
;   （"请先装 Microsoft Edge WebView2 运行时" + 官方下载链接），既不挡安装也不挡卸载。
; 安装包本身保持"复制文件 + 建快捷方式 + 卸载项"这么简单，谁都能一眼看懂、每一步都可验证。
; ---------------------------------------------------------------------------
