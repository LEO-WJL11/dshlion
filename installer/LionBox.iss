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
; 1.1.2：安装前自动停掉正在运行的 LionBox（旧版会弹「以下程序正在使用文件」那一页，
;        用户看到 Java(TM) Platform SE binary / llama-server 两个进程名，以为是报错）
; 1.1.1：模型对外名字改成 lion-models1（1.1.0 会显示底座模型名，别再用那个包）
; 1.1.4：修掉「模型说要调工具、结果什么都没发生」——解析器不认 Qwen 模板原生格式
;        <tool_call><function=名字><parameter=键>值</parameter></function></tool_call>
; 1.1.5：工具别再来一遍（重复调用/连续失败守卫）+ 工具参数签名进提示词 + 网络工具快速失败
; 1.1.6：修掉 HTTP 500「System message must be at the beginning」+ 工具通道回到原生（--jinja 默认是开的）
; 1.1.7：一轮最多 3 个互不依赖的工具调用（轮数砍到 1/3）+ 本地生成封顶 1024（不再出现 6 分钟一轮）
; 1.1.8：本地盒子改走文本通道（服务端会把多个 tool_call 块揉坏）+ 提示词给批量示例（一次 3 个）+ 生成长度撞顶自动升档
; 1.1.9：修文本通道下的参数类型 bug（数值参数以字符串给进来时工具直接 ClassCastException）
; 1.1.10：去掉 10 分钟硬超时（跑到第 70 个工具被砍断）+ 修 yaml_process/string_utils/git_stash/
;          execute_command/数值参数等失败
; 1.1.11：escape_string/timestamp/git_* 工具修复（正文塞进 target、strftime 被拆坏、
;          目录不存在被误报成没装 git）+ delete_file 支持 recursive + 工具名写错给候选
; 1.1.12：number_convert 认进制名（dec/hex/bin/oct）、word_count 传目录给明确错误、
;          stop_background 列出可用 pid、ask_user 超时下限 30 秒、补上 git_reset
; 1.1.13：GBK 文件读取容错（Input length = 1）、含只读文件的目录递归删除、
;          cmd/PowerShell 命令分流 + 输出 UTF-8、move/copy 参数别名、git_commit 兜底身份
; 1.1.14：glob_files 的 path 可选、modify_file 支持 append、
;          delete_file 拒绝删除工作区根目录（安全护栏）+ 递归删失败退回系统 rmdir
; 1.1.15：git_remote 支持 add/remove、问答接口暴露 timeoutSeconds（30 秒下限可验证）
; 1.1.16：修"工具卡住拖死整条消息" —— git 不再等凭据/联网（remote show 用 -n、
;          GIT_TERMINAL_PROMPT=0）、先 waitFor 再读输出、派发层加工具超时兜底
; 1.1.17：git_remote 支持 get-url/set-url；execute_command 把 Unix 写法翻成 PowerShell
;          （ls -la / rm -rf / cp -r / mkdir -p / grep / touch / which / ps aux 等）
; 1.1.18：写文件保留原编码（GBK 的 ANSI 中文文件改完仍是 GBK，不会被悄悄转成 UTF-8）
#define AppVersion     "1.1.18"
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
; 打包成**单个自包含 exe**：不分卷。
; 以前带 8.9GB 权重时必须分卷（单文件超 4GB 下载/文件系统都别扭），
; 现在权重改为首次使用时下载，包体只有 ~70MB，分卷反而害人 ——
; 用户只下 .exe 就会遇到 Inno 的 "Please insert Disk 1" 提示。
DiskSpanning=no
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#AppNameCN}
UninstallDisplayIcon={app}\{#AppExeName}
; 模型文件较大，给出磁盘空间下限
ExtraDiskSpaceRequired=0
; 升级安装时 LionBox 往往正在运行（javaw.exe + llama-server.exe 都跑在安装目录里），
; Inno 默认会弹「Preparing to Install / 以下应用程序正在使用文件」那一页，
; 用户看到 "Java(TM) Platform SE binary" 和 "llama-server" 两个名字，以为是程序报错。
; force = 直接关掉，不问；配合下面 [Code] 里的停服逻辑，用户一路 Next 就行。
CloseApplications=force
; 关掉之后不要由安装程序自动拉起：那样只有 Agent 起来、启动窗口横幅没有，
; 状态半截。改成让用户在完成页点「立即启动 LionBox」（走正常启动器）。
RestartApplications=no

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

// 安装前把正在运行的 LionBox 停掉。
// 优先用安装目录里自带的 stopper.ps1（逻辑已实测过：按 jar 名定位 Agent + 停 llama-server）；
// 没有（首次安装）或调用失败时，再用一条只针对安装目录内进程的兜底命令 ——
// 绝不能按进程名一把梭，用户机器上别人家的 java / llama-server 不能被误杀。
procedure StopRunningLionBox();
var
  ResultCode: Integer;
  Stopper: String;
  Cmd: String;
begin
  Stopper := ExpandConstant('{app}\stopper.ps1');
  if FileExists(Stopper) then
  begin
    if Exec('powershell.exe',
            '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + Stopper + '"',
            '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      Sleep(1200);
  end;

  Cmd := '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ' +
         '"Get-Process javaw,java,llama-server -ErrorAction SilentlyContinue | ' +
         'Where-Object { $_.Path -like ''' + ExpandConstant('{app}') + '\*'' } | ' +
         'Stop-Process -Force -ErrorAction SilentlyContinue"';
  Exec('powershell.exe', Cmd, '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(500);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopRunningLionBox();
  Result := '';
end;
