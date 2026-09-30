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
; 1.1.8：本地本地改走文本通道（服务端会把多个 tool_call 块揉坏）+ 提示词给批量示例（一次 3 个）+ 生成长度撞顶自动升档
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
; 1.2.0（功能版）：安装时可选模型版本（Q8_0/Q4_K_M/IQ4_XS）；设置页可改 llama.cpp 全部参数；
;                 对话列表按工作区分组成可收起的选项卡，每个对话能选工作区
; 1.2.1：侧栏加「清空所有对话」按钮（两次确认，工作区保留）+ DELETE /api/sessions
; 1.2.2：修「安装时选了 IQ4，启动却跑 Q8_0」——启动时改为「先认你选的 → 缺了就下它 → 实在下不来才兜底」，
;         并且界面如实说明在用哪个、为什么不是你要的那个；设置页可直接点「立即下载并切换」
; 1.2.3：对话列表里**没有对话的工作区不再显示**（只是不占位置，工作区注册和磁盘文件夹都不动）
; 1.2.4：设置页可以**同时下载好几份模型**再挑一份用；把**模型目录**显示出来并能一键打开，
;         用户自己丢进去的 .gguf 也会列出来直接可用
; 1.2.5：工作模式**只留「标准」和「极简」**（PTC 和创造模式不再给用户选；老会话照常加载，
;         自动按标准模式跑）
; 1.2.6：模型下载**放到前台，带进度条**（文件名 / 百分比 / 已下总量 / 速度 / 剩余时间），
;         下完或失败都在条上说明；设置页里正在下的那一行也有小进度条
; 1.2.7：**前台常驻「模型」面板**（输入框上方）—— 挑版本、点下载、看进度都在前台，
;         不用翻设置；你选的那份还没下载时它会自己摊开
; 1.2.8：模型下载**改成前台窗口**：点「使用」/「下载」就弹软件自己的下载窗口，
;         进度条/速度/剩余时间都在里面走，下完当场给「立即使用」（不再用浏览器原生提示）
; 1.2.9：**设置里的模型一栏重做**：一份模型一张卡（一句话说清 + 一个按钮），
;         顶部一句"当前在用哪份"，llama.cpp 那堆参数收进「高级」折叠块
; 1.3.0：网络搜索改成**无头浏览器**（用系统里已装的 Edge/Chrome，不要 API 密钥）；
;         修好用户实测坏掉的工具：git_diff 带文件参数、git_remote add 不给名字、
;         git_log 空仓库、git_commit 无改动、modify_file 只给原文、word_count 非文本文件、translate 免密钥
; 1.3.1：**删掉会中断任务的东西**（同一工具同参数 5 次就终止任务、连续失败就跳过不执行、
;         一轮最多 3 个工具调用多余的丢掉、轮次过多自动停止）；
;         工具调用显示**统一成一行式**（🔧 调用工具：X … / ✅ X 完成 / ❌ X 失败），【系统提示】不再显示在对话里
; 1.4.0：① **execute_command 改成"一个持续运行的终端"**（同一工作区共用一个 PowerShell 会话，
;         cd / 变量 / 函数都留到下一次调用，不再每条命令新开一个进程）；
;         ② **标准模式 = 全部 55 个工具，极简模式 = 只有文件类 + shell 类工具**（21 个，
;         以前 web_search 写死了"永远可用"，极简模式里也能调，现在按模式真过滤）；
;         ③ 修报错工具：参数是字符串时的强转崩溃（head_tail_file 的 lines 等）、line_count 读 GBK 文件、
;         modify_file operation=create、git_branch 在空仓库里建分支、stop_background 不给 pid；
;         ④ 修界面**工具行重复刷屏**（web_search 出现 9 次、ask_user 20 次）
; 1.4.1：**不再做盒子（硬件一体机）了** —— 把代码/文档/界面里"盒子/硬件一体机/随盒子交付"
;         这类说法全改成"本地模型运行时"（**只动文案，判断逻辑与端点限制一行没改**）；
;         硬件板的设计文档和调研资料已从项目里删掉
#define AppVersion     "1.4.1"
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
; 压缩用满 16 线程（这台是 16 核 16 线程）。LZMA2 支持多线程压缩：
; 多线程压缩：LZMA2 用满 16 线程（本机 16 核 16 线程）。
; 【实测】solid 压缩是**单流**的，开了 SolidCompression=yes 时这个设置等于没用：
;   ultra64 + solid + 16 线程        31.0 秒   72.19 MB
;   ultra64 + 不 solid + 16 线程     23.6 秒   73.96 MB   ← 现在用这套
;   lzma2/max + 不 solid + 16 线程   22.2 秒   74.07 MB
; 代价是数据切成多块（块大小 = 总量 / 线程数），包大 1.8 MB；换 7.4 秒压缩时间，
; 对"改一点就出一次包"是值的。要极致小体积就把 SolidCompression 改回 yes。
LZMANumBlockThreads=16
SolidCompression=no
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

// ==================== 选择模型版本 ====================
// 安装包里不再内置权重，首次用到时从 ModelScope 下载。三份量化体积差一倍，
// 让用户在装的时候就选好（默认 Q8_0，质量最好）。
// 选择写到 ~/.lioncode/install-model.txt，应用启动时读它并写进 llama.modelFile。
var
  ModelPage: TInputOptionWizardPage;

procedure InitializeWizard();
begin
  ModelPage := CreateInputOptionPage(wpSelectTasks,
    '选择模型版本',
    '要下载哪一份模型权重？',
    '安装包里不含权重（所以只有 72 MB）。首次使用本地模型时会自动下载你选的这一份，' +
    '之后可以在「设置 → 本地模型 / llama.cpp」里随时换。',
    True, False);
  ModelPage.Add('Q8_0 —— 质量最好（推荐，8.87 GB）');
  ModelPage.Add('Q4_K_M —— 体积小 40%、速度快约 1.7 倍（5.24 GB）');
  ModelPage.Add('IQ4_XS —— 最小最快，质量下降明显（4.87 GB）');
  ModelPage.SelectedValueIndex := 0;
end;

// 把选择落盘。静默安装（/VERYSILENT）时向导页不会创建，此时按默认 Q8_0 写。
procedure WriteModelChoice();
var
  Dir, Path, Choice: String;
begin
  if ModelPage = nil then
    Choice := 'lion-merged-Q8_0.gguf'
  else
    case ModelPage.SelectedValueIndex of
      1: Choice := 'lion-merged-Q4_K_M.gguf';
      2: Choice := 'lion-merged-IQ4_XS.gguf';
    else
      Choice := 'lion-merged-Q8_0.gguf';
    end;

  // 注意：Inno 没有 {userprofile} 这个常量（实测会报 Unknown constant）。
  // 用 GetEnv 读环境变量，和应用侧 System.getProperty("user.home") 取的是同一个目录。
  Dir := GetEnv('USERPROFILE') + '\.lioncode';
  if not DirExists(Dir) then
    ForceDirectories(Dir);
  Path := Dir + '\install-model.txt';
  if SaveStringToFile(Path, Choice, False) then
    Log('已记录模型选择: ' + Choice)
  else
    Log('写入模型选择失败: ' + Path);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    WriteModelChoice();
end;
