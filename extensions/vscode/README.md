# LionBox for VS Code

把 **VS Code 右侧栏（secondary sidebar）** 换成 LionBox 的 Agent 面板：不用跳出去，
在编辑器里就能让 Agent 干活，而且三件事跟着编辑器走：

| 能力 | 说明 |
| --- | --- |
| 工作区 = 打开的文件夹 | 插件把当前打开的文件夹交给后端建/复用工作区；换文件夹就换工作区、自动开新会话 |
| @ 引用单个文件 | 面板里点「@ 文件」挑一个，插成 `@file:相对路径`；后端**只读这一个文件**进上下文（窗口默认才 16K，省着用） |
| 改动人工审核 | AI 改的文件先攒成待审改动，面板里看 diff，点「通过并写入」才落盘；点「打回」写理由，AI 按理由重写 —— 相当于作业签字 |

命令：`LionBox: 打开 Agent 面板（右侧栏）`、`新建对话`、`@ 引用一个文件`、`打开完整 Web UI`、`重新检测后端并刷新`。

前提：本机跑着 LionBox（主程序或桌面版），后端默认 `http://127.0.0.1:8080`
（设置项 `lionbox.baseUrl` 可改）。

# LionBox for VS Code

在 VS Code 里以 Webview 面板打开 LionBox（Lion-Code Agent Harness）现有的本地 Web UI
（默认 `http://127.0.0.1:8080`）。**不重写界面**，只做一层壳。

```
extensions/vscode/
├── package.json          # 扩展清单（contributes.commands / contributes.configuration / main）
├── src/extension.js      # 全部逻辑（纯 JS，无需编译）
├── .vscodeignore         # 打包时排除开发文件
├── .vscode/launch.json   # F5 调试扩展宿主
└── README.md
```

## 1. 版本来源（2026-09-30 核实）

| 项 | 取值 | 来源 |
| --- | --- | --- |
| `engines.vscode` | `^1.90.0` | 兼容性由 `engines.vscode` 决定：[Publishing Extensions → VS Code compatibility](https://code.visualstudio.com/api/working-with-extensions/publishing-extension) |
| `@types/vscode`（仅开发期提示） | 精确锁 `1.90.0` | npm registry（当日最新为 1.138.0；这里精确锁到 engine 下限，避免 vsce 的 "@types 高于 engines" 提示） |
| vsce | `4.0.0`（当日最新） | `npm view @vscode/vsce version`；[vsce 仓库](https://github.com/microsoft/vscode-vsce) |
| 当前 VS Code Stable | 1.139（2026-09-23 发布） | [1.139 Release Notes](https://code.visualstudio.com/updates/v1_139) |
| 关键 API | `vscode.window.createWebviewPanel` / `WebviewPanel.webview.html` / `webview.options.enableScripts` / `retainContextWhenHidden` / `webview.onDidReceiveMessage` | [Webview API](https://code.visualstudio.com/api/extension-guides/webview)、[API 参考](https://code.visualstudio.com/api/references/vscode-api#window.createWebviewPanel) |

## 2. 构建与本地安装（确切命令）

```powershell
cd C:\Users\Leo\Desktop\lion-code\extensions\vscode

# 可选：装 @types/vscode 以获得智能提示（运行期不需要）
npm install

# 打包成 .vsix（首次会自动下载 vsce）
npx --yes @vscode/vsce package
# 产物：lionbox-0.1.0.vsix

# 本地安装
code --install-extension .\lionbox-0.1.0.vsix
```

安装后 `Ctrl+Shift+P` → 执行 **LionBox: 打开 LionBox Web UI**。

### 开发期调试（F5）
用 VS Code 打开 `extensions/vscode` 目录，按 `F5`（配置见 `.vscode/launch.json`），
会启动一个"扩展开发宿主"窗口，在新窗口里执行上面那条命令即可。

## 3. 后端没起来时会怎样

扩展会 `fetch(<baseUrl><healthPath>)`（默认 `http://127.0.0.1:8080/api/runtime/mode`）：

- **收到任何 HTTP 响应**（200/401/404…）→ 认为端口已监听 → 显示 iframe；
- **连接被拒 / 超时** → 面板显示"连不上 http://127.0.0.1:8080" + 启动命令提示 + 「重新检测」按钮，
  同时右下角弹一条 warning；按钮点击后重新探测，成功后自动换成 iframe。

判定逻辑在 `src/extension.js` 的 `isBackendUp()`。

## 4. 为什么用 iframe 而不是直接把页面塞进 `webview.html`

LionBox 的 Web UI 里全是相对路径请求（`fetch('/api/chat')`、`fetch('/api/events/…')`…）。
如果直接把 HTML 字符串赋给 `webview.html`，页面源会变成 `vscode-webview://…`，
所有 `/api/*` 都会打到错误的主机上。放进 `<iframe src="http://127.0.0.1:8080/">` 后，
页面源仍是后端自己，相对路径、SSE/轮询、Cookie 全部天然正确。

**CSP 必须显式声明**（官方 Webview 文档要求扩展为非本地内容设置 CSP）：
本扩展注入的 HTML 里写死了

```
default-src 'none'; frame-src http://127.0.0.1:8080 http://localhost:8080;
style-src 'nonce-…'; script-src 'nonce-…'
```

注意 `frame-src` 是**必须**的——不放行的话 iframe 会被直接拦掉。
另外 `http://127.0.0.1` / `http://localhost` 在 Chromium 里属于 *potentially trustworthy origin*，
因此不会被混合内容策略当成"不安全内容"拦截（换成局域网 IP 就会是另一个故事）。

> 若后端以后引入了 Spring Security，默认的 `X-Frame-Options: DENY` 会让 iframe 空白，
> 需要显式放开 `frameOptions`。当前 `pom.xml` 没有 spring-security 依赖，无此问题。

## 5. 可配置项（`contributes.configuration`）

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| `lionbox.baseUrl` | `http://127.0.0.1:8080` | 后端地址，换端口只改这里 |
| `lionbox.healthPath` | `/api/runtime/mode` | 就绪检测路径 |
| `lionbox.probeTimeoutMs` | `1500` | 单次探测超时（毫秒） |

改完设置会自动重载面板（`onDidChangeConfiguration`）。

## 6. 打包与发布（确切命令）

```powershell
# 打包（产出 .vsix）
npx --yes @vscode/vsce package

# 发布到 Marketplace（需要 publisher + 凭据）
npx --yes @vscode/vsce login lioncode
npx --yes @vscode/vsce publish
npx --yes @vscode/vsce publish patch     # 自动把 version 从 0.1.0 提到 0.1.1

# 预发布通道（要求 engines.vscode >= 1.63.0）
npx --yes @vscode/vsce publish --pre-release

# 手动上传（不上 Marketplace 的私有分发）
# 把 .vsix 交给对方：code --install-extension lionbox-0.1.0.vsix
```

官方要求与注意事项（均见 [Publishing Extensions](https://code.visualstudio.com/api/working-with-extensions/publishing-extension)）：

- `publisher` 字段（本工程为占位 `lioncode`）必须与 Marketplace 上创建的 publisher ID 一致；
- **重要**：Azure DevOps 的全局 PAT 将在 **2026-12-01 退役**，官方推荐改用 Entra ID +
  workload identity federation，用 `vsce publish --azure-credential` 发布；
- 图标不能是 SVG，README/CHANGELOG 里的图片必须是 https；
- `.vscodeignore` 用于瘦身；若以后引入依赖，建议按
  [Bundling Extensions](https://code.visualstudio.com/api/working-with-extensions/bundling-extension)
  用 esbuild 打包成一个文件。
- 未发布前也可用 `.vsix` 直接分发（本工程走的就是这条路）。

## 7. 已知限制 / 风险

- **远程开发**：Remote-SSH / Dev Containers / Codespaces 场景下扩展宿主在远端，
  `127.0.0.1:8080` 指的是**远端机器**。要么在远端跑后端，要么让用户改成转发后的地址。
- Webview 是重资源，官方明确建议"非必要不用"；`retainContextWhenHidden: true` 会进一步吃内存
  （代价是切走再切回不丢聊天状态，值得）。
- 后端页面若将来改用绝对 URL 或登录态，需要重新评估 CSP 与 Cookie 策略。
