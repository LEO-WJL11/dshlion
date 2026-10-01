#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1.5.3 文档：上下文经济 + 改动人工审核 + VS Code 右侧栏 Agent。

【为什么用 Python 改文档】PowerShell 按 GBK 读写中文会写成乱码（这一轮栽过两次），
带中文的文件一律走 Python。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

NEW_DOC = '''# 上下文窗口、上下文裁剪、改动人工审核

这一版（1.5.3）给 Agent 加了两个"省钱"的工具，以及一道人工闸门。

## 一、上下文窗口默认 16K，Agent 可以自己调

**为什么默认砍到 16K**：本机模型原生 256K，但每一条消息都要把整个前缀**重新预填充**一遍 ——
窗口开多大，每一轮就多算多少。16K 够干绝大多数活，预填充只有 256K 的十六分之一。
配置项：`lionbox.agent.context-limit-tokens`（默认 16384，0 = 不设限，回到"模型窗口 × 75%"）。

**Agent 怎么调**：工具 `context_window`。

| 参数 | 说明 |
| --- | --- |
| `tokens` | 新的窗口大小。16384 = 默认；调大用于大项目；0 = 不设限 |
| `reason` | 为什么需要调（记进日志，事后能看这笔开销值不值） |

调完**立刻生效**（下一次模型调用就用新窗口），而且是**按会话**记的（别的会话不受影响）。
下限 4096（再小连系统提示和工具定义都放不下，会陷入反复压缩），上限 196608。

**给模型的约束**（写在系统提示的「上下文怎么用才省钱」一节里，原文）：

> 1. 默认什么都别做。16K 够干绝大多数活，不要一上来就调窗口。
> 2. 只有在**真的装不下**的时候才调大：比如要通读一个几千行的文件、或者同时盯好几个模块、
>    或者已经压缩过两次还在原地打转。调的时候给一句 reason 说明为什么。
> 3. **用完立刻还**：那件事做完，马上把窗口调回 16384。忘了还，用户后面每一句话都要多花这笔预填充的钱。
> 4. 省上下文的优先顺序（从便宜到贵）：
>    ① 只读你要改的那一段（read_file 带行号范围 / head_tail_file），别整文件读；
>    ② 探查完就裁剪：方案定了、前面翻文件试错的过程没用了，用 context_prune 保留最近几条；
>    ③ 还不够，最后才考虑调大窗口。
> 5. 裁剪前想先看看会删掉什么，用 context_prune 的 dry_run=true。

界面上也能改：输入框下面那个「窗口 16K」点一下就是设置入口（`GET/POST /api/context`）。

## 二、Agent 可以删掉自己没用的上下文

工具 `context_prune`：保留最近 `keep_last` 条，更早的**真删**（从会话历史移除并落盘，
不是只影响这一次请求）。和自动压缩的分工：压缩是"塞不下了自动折叠成摘要"，
裁剪是"模型自己知道前面那些没用了"。

两个保护：
- **不越过工具调用的配对边界**：切点会挪到安全位置，不会留下孤儿 tool 结果（有些 API 直接 400）；
- **至少保留最近 2 条**，system 消息永不删。

`dry_run=true` 只报告会删掉什么（列出前 12 条摘要）。

## 三、改动人工审核（AI 改的文件，你点通过才落盘）

用户原话："人工去审核，审核通过了这个文件才会真正被使用，否则打回去重写，相当于作业。"

**怎么工作**：
1. 改文件的五个工具（`write_file` / `modify_file` / `append_file` / `create_file` / `delete_file`）
   在写之前先过闸门：把改动登记成一条**待审记录**（含 diff），工具返回
   "已提交人工审核，**还没落盘**"；
2. 界面上（WebUI 输入框上方的待审区、VS Code 右侧栏面板）列出 diff，两个按钮；
3. **通过** → 真写进磁盘，并往会话里插一条系统提示（模型下一轮知道已经生效）；
4. **打回** → 不落盘，把理由回给模型（"被打回，请按这个理由重写"）—— 作业被打回。

**开关**：插件 `plugin.change-review`（设置 → 插件管理 → 授权审查那一类），**默认开**。
关掉就回到老行为：直接落盘。配置文件兜底项 `lionbox.change-review.enabled`。

**和"自动授权审查插件"的区别**：那个是动手**之前**让另一个模型判断该不该放行；
这个是动完手**之后**、落盘之前等人点头。两个都开就是双保险。

**接口**：
```
GET  /api/changes?sessionId=&includeDecided=   待审列表（带 diff）
GET  /api/changes/{id}                          单条详情（含改前/改后全文）
POST /api/changes/{id}/approve                  通过并写入
POST /api/changes/{id}/reject  {"reason": "..."} 打回
GET  /api/context?sessionId=                    当前窗口
POST /api/context  {"sessionId": "...", "tokens": 32768}
```

## 四、VS Code：右侧栏就是 Agent

插件不再只是"开个面板内嵌 Web UI"，而是把 **secondary sidebar（最右边那一栏）** 换成 Agent 面板：

- **工作区 = 你打开的文件夹**：插件把 `workspaceFolders[0]` 交给后端建/复用工作区，
  换文件夹就换工作区、自动开新会话；
- **@ 引用单个文件**：面板里点「@ 文件」挑一个，插成 `@file:相对路径` ——
  后端只把这一个文件读进上下文（16K 窗口下这一条很关键）；
- **改动就在面板里审**：列出 diff，「通过并写入」/「打回」直接点；
- 想用完整界面还有「Web」按钮（内嵌整个 Web UI）。

命令：`LionBox: 打开 Agent 面板` / `新建对话` / `@ 引用一个文件` / `打开完整 Web UI` / `重新检测后端并刷新`。
文件：`extensions/vscode/`（清单挂在 `contributes.viewsContainers.secondarySidebar`）。
'''

NOTE = '''
## 2026-10-01 · 第 43 轮：上下文窗口默认 16K + Agent 自己裁剪 + 改动人工审核 + VS Code 右侧栏 Agent

### 149. 用户要的四件事（原话）

> "就是那个插件栏，右边最右边那一栏，你换成 agent，就我们这个 agent，其他功能不变。"
> "这个 agent 的工作区就是这个左边这个 VS Code 现在打开的这个项目……这个 AI 写文代码可以，
> 就是可以人工去审核，审核通过了同意了，他才会真正的被使用，否则的话这段代码修改就不会被真正的使用，
> 就是会被打回去重写，相当于作业。"
> "也可以手动引用，@ 引用某一个项目里的文件，这样的话就只加载这个文件到上下文中，省一些上下文窗口。"
> "上下文窗口给它改成默认 16K，然后给一个工具，AI 使用了这个工具就可以调整它自己的上下文窗口，
> 调整了之后就真的去调整。你写段提示词约束 AI 这个工具怎么用。"
> "再给一个工具……可以让 AI 自己手动删掉，就是可以选择删除上下文的某一部分，保留后面这几条。"

四件都做了，而且都是"真生效"而不是画个 UI。

### 150. 上下文窗口：默认 16K + 模型自己能调

- 新增 `ContextBudget`（全局默认 16384 + 每会话覆盖，覆盖只在内存里 —— "临时为大项目开大窗口"
  本来就不该变成永久设置）；`AgentLoop.effectiveContextLimit` 的判定顺序变成
  **会话覆盖 → 全局默认 → 模型窗口 × 75%**。
- 新增工具 `context_window`（tokens / reason），改完立刻生效；下限 4096、上限 196608；
  返回值里会再提醒一次"用完调回 16384"。
- **提示词约束**写在系统提示的「上下文怎么用才省钱」一节（只在模型确实有这两个工具时才注入，
  插件关掉就不会出现"教它用一个不存在的工具"）。
- 界面上加了「窗口 16K」指示（点击可改），接口 `GET/POST /api/context`。

### 151. 上下文裁剪：`context_prune`

真删（从会话历史移除并落盘）：保留最近 `keep_last` 条，更早的全删。
两个保护：切点会挪到**工具调用配对的安全边界**（不留孤儿 tool 结果）；至少保留 2 条、system 不删。
`dry_run=true` 只报告会删什么。

### 152. 改动人工审核：`plugin.change-review`（默认开）

- `ChangeReview` 服务：登记待审改动（带行级 diff，自己写的 LCS，超过 4000 行退化成"整文件替换"摘要）、
  `approve` 真落盘并往会话插系统提示、`reject` 不落盘并把理由回给模型。
- 闸门接在五个改文件的工具上（write/modify/append/create/delete），命中就返回"还没落盘"。
- 九个接口/界面入口：`/api/changes`（列表/详情/通过/打回）+ WebUI 待审区 + VS Code 面板。
- WebUI 输入框上方新增待审区（diff 上色，通过/打回按钮），复用现成的 800ms 轮询，不另开定时器。

### 153. VS Code：右侧栏换成 Agent

`contributes.viewsContainers.secondarySidebar` + webview 视图；面板自己做（不是内嵌整个 Web UI）：
工作区=打开的文件夹、@ 引用单文件（`@file:`，后端只读那一个文件进上下文）、
待审改动直接在面板里通过/打回、还能一键开完整 Web UI。

### 154. 验到什么程度

- `tools/checks/_check_context_review.py`（**26 条断言，全过**，用假模型驱动，确定性）：
  默认 16K；工具真把窗口改成 32K 且**别的会话不受影响**；能调回去；太小被拒；
  裁剪后**会话历史真的从 12 条变 4 条**；写入进待审且**文件确实没落盘**；通过后文件真出现且状态 APPROVED；
  打回后文件**永远不落盘**、理由记在记录里、会话里留下"被打回请重写"的提示；
  关掉插件后恢复"直接落盘"的老行为。
- `tools/checks/_check_vscode_ext.py`（**29 条**）：视图挂在 secondarySidebar、图标文件真在、
  五个命令清单与代码一致、activationEvents 正确、工作区/@引用/通过/打回的关键调用都在、
  面板内联 JS 过 node --check。
- `tools/release/_verify_153_ui.py`（**22 条**）：把 1.5.3 主安装包装到临时目录、用**装出来的自带 JRE**
  起服务，断言页面与仓库逐字节一致、待审区与窗口指示都在、`/api/context` 默认 16384、
  `/api/changes` 默认 enabled=true、六个系统插件 + 新的改动审核插件都在、卸载干净。
- 全量回归：**40 个套件 0 失败**。

### 155. 这一轮踩到的坑

1. **两个同名参数叠加导致起不来**：`_app.py` 默认传 `--lionbox.change-review.enabled=false`，
   用例又传 `=true`，Spring 把它读成 `"false,true"` → `Invalid boolean value`。
   改成"extra_args 里给了同一个键就以调用方为准"。
2. **CRLF 让多行锚点失效**：`edit` 工具的多行匹配在 CRLF 文件上打不中（web/index.html），
   改用 Python 归一化后再替换。
3. **"是否已插入"的判据不能用锚点自己**：连着两次把"新内容第一行"当判据，
   而那行恰好就是锚点 → 永远判断成"已有"，样式和轮询挂载都漏了。改用新内容里独有的标记。
4. **插件要显式注册才会出现在插件列表里**：新插件类加了 `@Component` 还不够，
   必须加进 `PluginBootstrap` 的 `systemPlugins` 列表，否则 `/api/plugins/.../disable` 会回
   "插件不存在"（用例就是这么抓出来的）。
5. **写 Java 字符串时中文引号混进 ASCII 引号**：两处 `"` 把字符串截断，编译报"非法字符"。
   测试里把这条记下来：中文内容一律用「」而不是英文双引号。
'''

# ---------------- 1) 新文档 ----------------
p = os.path.join(ROOT, 'docs', '上下文与改动审核.md')
io.open(p, 'w', encoding='utf-8', newline='').write(NEW_DOC)
print('已写 docs/上下文与改动审核.md')

# ---------------- 2) 自测记录追加 ----------------
p = os.path.join(ROOT, 'docs', '软件自测记录.md')
s = io.open(p, encoding='utf-8', newline='').read()
if '第 43 轮' in s:
    print('软件自测记录：已有第 43 轮')
else:
    nl = '\r\n' if '\r\n' in s else '\n'
    body = NOTE.replace('\n', nl) if nl == '\r\n' else NOTE
    io.open(p, 'w', encoding='utf-8', newline='').write(s.rstrip() + nl + body)
    print('软件自测记录：已追加第 43 轮')

# ---------------- 3) 界面说明补充 ----------------
p = os.path.join(ROOT, 'docs', '界面说明.md')
s = io.open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'
extra = [
    '',
    '## 输入框下方（1.5.3 起）',
    '',
    '- **窗口 16K**：当前会话的上下文窗口，点一下能手动改（默认 16K 是为了省预填充；',
    '  Agent 也能用 `context_window` 工具自己调，调完界面上会跟着变）。',
    '- 输入框**上方**会出现「⏳ 有 N 个改动等你审核」：AI 改文件后先攒在这里，',
    '  每条带 diff（+ 绿 / - 红），点「通过并写入」才真正落盘，点「打回并说明理由」会带着理由让 AI 重写。',
    '  详见 [上下文与改动审核.md](上下文与改动审核.md)。',
    '',
]
if '窗口 16K' in s:
    print('界面说明：已提过 1.5.3 的改动')
else:
    body = nl.join(extra)
    io.open(p, 'w', encoding='utf-8', newline='').write(s.rstrip() + nl + body)
    print('界面说明：已补充待审改动 + 窗口指示')

# ---------------- 4) VS Code 插件 README ----------------
p = os.path.join(ROOT, 'extensions', 'vscode', 'README.md')
s = io.open(p, encoding='utf-8', newline='').read()
nl = '\r\n' if '\r\n' in s else '\n'
head = ('''# LionBox for VS Code

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

''')
if 'secondary sidebar' in s:
    print('VS Code README：已更新过')
else:
    body = head.replace('\n', nl) if nl == '\r\n' else head
    io.open(p, 'w', encoding='utf-8', newline='').write(body + s.lstrip())
    print('VS Code README：已重写开头')

print('文档更新完成')
sys.exit(0)
