# LionBox 桌面套壳（Electron）

把现有单文件 Web UI（`web/index.html`）**原样**装进桌面窗口的 Electron 最小工程。
不重写界面、不复制前端代码：主进程等后端就绪后直接 `loadURL('http://127.0.0.1:8080')`。

```
desktop/electron/
├── package.json          # 依赖与脚本（electron ^44.5.1 / electron-builder ^26.15.3）
├── main.js               # 主进程：窗口、安全基线、健康轮询、可选拉起后端 jar
├── preload.js            # contextBridge 白名单 API（渲染进程拿不到 Node）
├── status.html           # "正在等待本地服务" 等待页（失败时显示原因 + 重试按钮）
├── electron-builder.yml  # 打包/安装包/签名配置（含"随包带 jar"的注释示例）
└── README.md
```

## 1. 版本来源（2026-09-30 核实）

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| Electron | `^44.5.1` | `npm view electron version`；[官方发布日程](https://releases.electronjs.org/schedule)（44.0.0 于 2026-08-25 进入 Stable，45.0.0 计划 2026-10-20） |
| electron-builder | `^26.15.3` | `npm view electron-builder version`；[官方文档](https://www.electron.build/) |
| 安全基线 | `contextIsolation:true` / `nodeIntegration:false` / `sandbox:true` | [Electron Security](https://www.electronjs.org/docs/latest/tutorial/security) |
| 打包方式 | Electron Forge 或 electron-builder | [Packaging Your Application](https://www.electronjs.org/docs/latest/tutorial/tutorial-packaging)（官方明确 Electron 核心不带打包工具） |

## 2. 构建与运行（确切命令）

```powershell
cd C:\Users\Leo\Desktop\lion-code\desktop\electron

# 安装依赖（首次约 100MB+，会下载 Electron 二进制）
npm install

# 开发运行：直接开窗口加载 8080
npm start
```

> 本机如果在受限沙箱/代理下 npm 写不了默认缓存目录，可把缓存指到工程内：
> `npm install --cache .\.npm-cache`，并设置 `$env:electron_config_cache="$PWD\.electron-cache"`。

## 3. 如何指向本地 8080 / 如何启动后端 jar

主进程**所有**外部输入都走环境变量，默认值就是本地 8080：

| 环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| `LIONBOX_URL` | `http://127.0.0.1:8080` | Web UI 地址（改了端口只改这个） |
| `LIONBOX_HEALTH_PATH` | `/api/runtime/mode` | 健康检查路径 |
| `LIONBOX_HEALTH_TIMEOUT_MS` | `120000` | 等待后端就绪的总超时（毫秒） |
| `LIONBOX_HEALTH_INTERVAL_MS` | `800` | 轮询间隔（毫秒） |
| `LIONBOX_JAR` | 空 | 后端 fat jar 路径；设了就自动 `java -jar` 拉起 |
| `LIONBOX_JAVA` | `java` | java 可执行文件（可指向某个特定 JRE） |
| `LIONBOX_AUTO_START` | `1` | 设为 `0` 则完全不自动拉起后端 |

三种典型用法：

```powershell
# A. 后端已在跑（最常见）：什么都不用设
npm start

# B. 让套壳自己把后端拉起来（开发期直接指向 target 下的 fat jar）
$env:LIONBOX_JAR = "C:\Users\Leo\Desktop\lion-code\target\lion-code-agent-harness-1.0.0-SNAPSHOT.jar"
npm start

# C. 后端换端口
$env:LIONBOX_URL = "http://127.0.0.1:9090"
npm start
```

后端 jar 的解析顺序（`main.js` 的 `resolveBackendJar()`）：
1. `LIONBOX_JAR` 显式指定；
2. 打包后：`resources/backend/lion-code-agent-harness.jar`（由 `electron-builder.yml` 的 `extraResources` 放入）；
3. 开发期：`desktop/electron/backend/lion-code-agent-harness.jar`。

自动拉起的 java 子进程会在 `before-quit` / `will-quit` 时被 `kill()`，不会留下孤儿进程。

## 4. "本地服务已就绪再加载页面" 是怎么做的

`main.js` 的启动顺序（`runStartupSequence()`）：

1. `win.loadFile('status.html')` —— 先显示本地等待页，**绝不**直接 `loadURL` 一个还没起来的服务；
2. `waitForBackend()` 用 `http.get(BASE_URL + '/api/runtime/mode')` 轮询；
   **判定规则：收到任何 HTTP 响应（200/401/404 都行）= 端口已监听 = 就绪**；只有 `ECONNREFUSED` / 超时才继续等；
3. 每次探测通过 `webContents.send('lionbox:status', …)` 把进度推给等待页（preload 转发给页面）；
4. 就绪后 `await mainWindow.loadURL(BASE_URL)`；
5. 超时则等待页切到失败态，显示原因 + 「重试」按钮（IPC `lionbox:retry` 重跑整个流程）。

## 5. 安全性（对照官方 Security 检查表）

| 官方要求 | 本工程做法 |
| --- | --- |
| 不给远程内容开 Node 集成 | `nodeIntegration:false`、`nodeIntegrationInWorker:false`、`nodeIntegrationInSubFrames:false` |
| 启用上下文隔离 | `contextIsolation:true`，页面只能看到 `preload.js` 里 `contextBridge` 暴露的 `window.lionbox`（`onStatus/retry/openExternal/versions`） |
| 启用进程沙箱 | `sandbox:true` |
| 不关闭 `webSecurity`、不开 `allowRunningInsecureContent` | 均为默认安全值，代码里显式写死 |
| 限制导航与新窗口 | `will-navigate` 只放行 Web UI 同源；其余交给 `shell.openExternal`；`setWindowOpenHandler` 一律 `{action:'deny'}` |
| 校验 IPC 来源 | `isTrustedSender()`：必须是主窗口的 webContents 且当前帧是 `file://`（即我们自己的等待页） |
| 默认拒绝权限请求 | `setPermissionRequestHandler` / `setPermissionCheckHandler` 只对本机 UI 源放行 `clipboard-sanitized-write`（复制按钮要用），其余全拒 |
| 定义 CSP | 等待页自带 `default-src 'none'` 的 meta CSP；Web UI 自身页面由后端提供，未做改动 |

## 6. 打包与发布（确切命令）

```powershell
# 只出免安装目录，最快，先验证一遍
npm run pack

# 出安装包（Windows: NSIS + portable；macOS: dmg；Linux: AppImage/deb）
npm run dist

# 分平台（建议在对应平台上各跑一次，跨平台产物需 wine 等额外依赖）
npm run dist:win
npm run dist:mac
npm run dist:linux
```

产物在 `desktop/electron/dist/`。配置见 `electron-builder.yml`。

**签名（正式发布必须）**
- 官方原文："In order to distribute desktop applications to end users, we *highly recommend* that you **code sign** your Electron app … it is mandatory for the auto-update step"，见 [Packaging Your Application → Important: signing your code](https://www.electronjs.org/docs/latest/tutorial/tutorial-packaging) 与 [Electron Forge code signing](https://www.electronforge.io/guides/code-signing)。
- Windows：在 `electron-builder.yml` 的 `win` 下配 `certificateFile`/`certificatePassword`，或用 CI 环境变量 `CSC_LINK` / `CSC_KEY_PASSWORD`。
- macOS：`hardenedRuntime: true` + `entitlements` + `notarize: true`，否则 Gatekeeper 直接拦。

**自动更新**
- Electron 侧用 `autoUpdater`（`electron.autoUpdater`）或 `electron-updater`；electron-builder 生成的 `latest.yml` / `*.blockmap` 需要放到一个 HTTP 更新源。参见 [Publishing and Updating](https://www.electronjs.org/docs/latest/tutorial/tutorial-publishing-updating)。
- 本工程暂未接自动更新（脚手架保持最小），接入点建议放在 `app.whenReady()` 之后。

## 7. 已知限制 / 风险

- **JRE 不在包里**：`java -jar` 需要目标机自带 JRE 21+。若要做“双击即用”，需要另行 jlink/打包运行时并把 `LIONBOX_JAVA` 指向包内 java。
- **端口占用**：8080 被别的进程占用时，健康检查会"误判为就绪"（任何 HTTP 响应都算就绪）。如需严格判定，可把 `/api/runtime/mode` 的 JSON 解析出来再判断 `success === true`。
- **未签名产物**：Windows SmartScreen、macOS Gatekeeper 会拦截，只能右键"仍要运行"或去系统设置里放行。
- 加载 `http://127.0.0.1:8080` 不会触发混合内容拦截（`127.0.0.1` 是 Chromium 的 potentially-trustworthy origin），但如果以后把地址换成局域网 IP，Electron 会按普通 HTTP 处理，需要重新评估安全设置。
