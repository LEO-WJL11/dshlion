# LionBox Agent for VS Code

把 **VS Code 最右边那一栏（secondary sidebar）** 变成 LionBox 的 Agent 面板，
外加三件跟着编辑器走的能力：

| 能力 | 说明 |
| --- | --- |
| **工作区 = 你打开的文件夹** | 插件把 `workspaceFolders[0]` 交给后端建/复用工作区；换文件夹就换工作区、自动开新会话。不用手填路径 |
| **`@` 引用单个文件** | 面板里点「@ 文件」挑一个，插成 `@file:相对路径` —— 后端**只读这一个文件**进上下文（窗口默认才 16K，省着用）。也可以点命令面板的「@ 引用一个文件」把引用复制到剪贴板 |
| **改动要你点通过才落盘** | AI 改的文件先攒成待审改动，面板里看 diff（`+` 绿 / `-` 红）：点「通过并写入」才真写进磁盘；点「打回」写一句理由，模型按理由重写 —— 相当于作业签字 |

对话、待审改动、工作区路径全在同一栏里，不用跳去浏览器。

→ 项目本体：[LionBox（Lion-Code Agent Harness）](https://github.com/LEO-WJL11/dshlion) ·
配套模型：[lion-models1 @ ModelScope](https://modelscope.cn/models/lionnezha/lion-models)

---

## 怎么装上

**大多数情况不用手动装**：LionBox 安装包（`LionBox-Setup-*.exe`）里已经带上这个插件，
安装过程会自动调用 `code --install-extension` 装上。装没装成功看安装目录的
`vscode-extension\install-result.txt`。

手动装（VS Code 装在非常规位置、或想单独更新插件时）：

```powershell
# 文件在安装目录里
code --install-extension "<安装目录>\vscode-extension\LionBox-VSCode.vsix"

# 或者：VS Code → 扩展面板 → 右上角 ... → 从 VSIX 安装
```

前提：本机跑着 LionBox 后端，默认 `http://127.0.0.1:8080`（设置项 `lionbox.baseUrl` 可改）。
后端没起来时面板会直接说"后端没起来（先启动 LionBox）"。

## 命令

| 命令 | 作用 |
| --- | --- |
| `LionBox: 打开 Agent 面板（右侧栏）` | 聚焦到右侧栏的 Agent 面板 |
| `LionBox: 新建对话` | 起一条新会话（当前工作区） |
| `LionBox: @ 引用一个文件` | 挑文件并把 `@file:…` 复制到剪贴板 |
| `LionBox: 打开完整 Web UI` | 需要完整界面时，在编辑器旁开一个内嵌 WebUI 面板 |
| `LionBox: 重新检测后端并刷新` | 重新探测后端并重开会话 |

## 设置

| 设置 | 默认 | 说明 |
| --- | --- | --- |
| `lionbox.baseUrl` | `http://127.0.0.1:8080` | 后端地址，换端口只改这里 |
| `lionbox.healthPath` | `/api/runtime/mode` | 就绪检测路径（收到任何 HTTP 响应即视为就绪） |
| `lionbox.probeTimeoutMs` | `1500` | 单次探测超时（毫秒） |
| `lionbox.pollMs` | `1500` | 面板刷新间隔：轮询待审改动与会话历史 |

## 面板在做什么（实现要点）

- **面板是自己画的轻量对话界面**，不是把整个 WebUI 塞进侧栏（侧栏太窄）。
  数据全走后端 REST：`/api/workspaces`、`/api/sessions`、`/api/chat`、
  `/api/sessions/{id}/history`、`/api/changes`（列表）+ `/approve`、`/reject`；
  按 `pollMs` 轮询这几条，够用且不依赖 SSE。
- **审阅在面板内完成**：待审改动带逐行 diff，两个按钮直接打后端接口；打回理由用输入框收。
- **工作区处理**：`ensureWorkspace()` 先按路径在 `/api/workspaces` 里找，找不到才新建；
  监听 `onDidChangeWorkspaceFolders`，换文件夹就重开会话。
- **`@` 引用**：`workspace.findFiles` 列文件（跳过 `node_modules/.git/dist/target/out`，上限 3000），
  快速选择后插成相对路径的 `@file:` —— 后端认识这个语法，只把该文件读进上下文。
- **顺带保留了完整 WebUI 面板**（`lionbox.openWebUI`）：用 `<iframe>` 内嵌而不是把页面源码
  塞进 `webview.html`。原因：WebUI 里全是 `fetch('/api/…')` 相对路径，宿主一旦变成
  `vscode-webview://` 就全 404；放进 iframe 后页面源仍是后端，相对路径天然正确。

## 自己打包（如果要改它）

```powershell
cd extensions\vscode

# 我们的打包器（会自检 vsix 结构，见下）
python tools\release\_build_vsix.py 1.5.4
# 产物：installer\release\LionBox-VSCode-1.5.4.vsix

# 也可以用官方 vsce（需要联网装包）
npx --yes @vscode/vsce package
```

**vsix 的目录结构有硬要求**（踩过）：扩展文件必须在 `extension/` 目录下，包里还要有
`extension.vsixmanifest` 和 `[Content_Types].xml`。少了任何一样，安装时报的是
`extension/package.json not found inside zip`（这句提示很误导人，实际是结构问题）。
`_build_vsix.py` 打完会自检这三样。

调试：用 VS Code 打开 `extensions/vscode` 目录，`F5` 启动扩展开发宿主
（配置见 `.vscode/launch.json`），在新窗口里打开面板即可。

## 已知限制

- **远程开发**（Remote-SSH / Dev Containers / Codespaces）：扩展宿主在远端，
  `127.0.0.1:8080` 指的是**远端机器** —— 要么后端在远端跑，要么把 `lionbox.baseUrl`
  改成转发后的地址。
- 面板轮询（默认 1.5 秒）而不是推送：延迟可接受、实现简单；后端没有稳定的 SSE 端点给插件用。
- 后端没起来时面板只提示状态，**不会**替你启动后端（启动是主程序安装器的事）。
- 未发布到 Marketplace：以 `.vsix` 随安装包分发（`publisher` 目前是 `lioncode`）。
