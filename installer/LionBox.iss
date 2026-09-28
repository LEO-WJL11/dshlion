; ============================================================
;  LionBox 安装包脚本  (Inno Setup 6)
;
;  编译： ISCC.exe LionBox.iss
;  产物： release\LionBox-Setup-<版本>.exe
;
;  特性：
;    - **不内置模型权重**：装完只有 ~100MB（程序 + llama.cpp 运行时 + 自带 Java）
;      首次用到本地模型时由后端自动从 ModelScope 下载 lion-merged-Q8_0.gguf（约 8.9GB）
;      · 手动等价命令： modelscope download --model lionnezha/lion-models lion-merged-Q8_0.gguf --local_dir .
;      · 关闭自动下载（离线/内网）：lionbox.runtime.auto-download=false，自己把权重放进安装目录
;      · 权重文件缺失时程序会退回目录里现成的任何 .gguf（Q8 > Q6 > Q5 > Q4）
;    - 启动时不加载模型：发出第一条消息才加载；用自定义 API 则永不加载
;    - 默认安装到 %LOCALAPPDATA%\Programs\LionBox（无需管理员权限，模型也下到这里）
;    - 自动创建桌面与开始菜单快捷方式
;    - 卸载时保留用户数据（会话、工作区、配置），仅删除程序与已下载的模型
; ============================================================

#define AppName        "LionBox"
#define AppNameCN      "LionBox 本地 AI 助手"
#define AppVersion     "1.1.0"
#define AppPublisher   "LionBox"
#define AppExeName     "启动LionBox.bat"

[Setup]
AppId={{8E3F1C42-5B7A-4D91-9E26-7C4A1D8B6F30}
AppName={#AppNameCN}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; 安装到用户目录，避免 UAC 提权，普通用户可装
PrivilegesRequired=lowest
OutputDir=release
OutputBaseFilename=LionBox-Setup-{#AppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
; 默认 2GB 分卷，避免生成单个 >4GB 的 exe（部分文件系统/浏览器下载受限）
DiskSpanning=yes
DiskSliceSize=2000000000
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#AppNameCN}
UninstallDisplayIcon={app}\{#AppExeName}
; 模型文件较大，给出磁盘空间下限
ExtraDiskSpaceRequired=0

[Languages]
Name: "chinese"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: checkedonce

[Files]
; 启动器与停止脚本
Source: "..\dist\启动LionBox.bat";  DestDir: "{app}"; Flags: ignoreversion
Source: "..\dist\停止LionBox.bat";  DestDir: "{app}"; Flags: ignoreversion
Source: "..\dist\launcher.ps1";     DestDir: "{app}"; Flags: ignoreversion
Source: "..\dist\stopper.ps1";      DestDir: "{app}"; Flags: ignoreversion
; 使用说明
Source: "..\dist\使用说明.md";       DestDir: "{app}"; Flags: ignoreversion
Source: "..\dist\README-模型.md";    DestDir: "{app}"; Flags: ignoreversion
; 程序主体
Source: "..\dist\lion-code-agent-harness-1.0.0-SNAPSHOT.jar"; DestDir: "{app}"; Flags: ignoreversion
; 模型运行时（llama.cpp）
Source: "..\dist\runtime-vulkan\*"; DestDir: "{app}\runtime-vulkan"; Flags: ignoreversion recursesubdirs
; 随包自带的精简 JRE（约 52MB，jlink 生成）：有了它，用户机器上没装 Java 也能直接跑
Source: "..\dist\runtime-jre\*";     DestDir: "{app}\runtime-jre";     Flags: ignoreversion recursesubdirs
; 模型权重不在包里 —— 首次用到时自动从 ModelScope 下载（lion-merged-Q8_0.gguf，约 8.9GB）。
; 这样安装包从 5.2GB 降到 ~100MB，也避免把权重塞进每个用户的安装目录。
; 需要离线部署的话，把权重预先放到 {app}\ 或 {app}\models\ 即可，程序优先用本地现成的。

[Icons]
Name: "{group}\{#AppNameCN}";           Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{group}\停止 LionBox";            Filename: "{app}\停止LionBox.bat"; WorkingDir: "{app}"
Name: "{group}\使用说明";                Filename: "{app}\使用说明.md"; WorkingDir: "{app}"
Name: "{group}\卸载 {#AppNameCN}";       Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppNameCN}";      Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; 安装完成后由用户选择是否立即启动
Filename: "{app}\{#AppExeName}"; Description: "立即启动 LionBox"; Flags: postinstall nowait skipifsilent shellexec

[UninstallDelete]
; 仅清理运行时生成的日志；用户数据（会话/工作区/配置）保留
Type: filesandordirs; Name: "{app}\logs"

[Code]
// 安装前检查磁盘空间（程序 ~100MB；模型权重是首次使用时另外下载的 8.9GB）
function InitializeSetup(): Boolean;
var
  FreeMB, TotalMB, NeedMB: Cardinal;
begin
  Result := True;
  NeedMB := 500;
  if GetSpaceOnDisk(ExpandConstant('{sd}\'), True, FreeMB, TotalMB) then
  begin
    if FreeMB < NeedMB then
    begin
      if MsgBox('安装 LionBox 至少需要 500 MB 可用磁盘空间，当前仅剩 ' +
                IntToStr(FreeMB) + ' MB。' + #13#10 + #13#10 +
                '是否仍要继续安装？', mbConfirmation, MB_YESNO) = IDNO then
        Result := False;
    end;
  end;
end;
