# 🦁 LionBox（Lion-Code Agent Harness）

**本地优先的 AI 编程 Agent**：一个安装包装完就能用，界面是 VS Code 右侧栏（也有浏览器 WebUI），
模型跑在你自己机器上 —— 代码和数据不出本机。

配套模型（本机默认用的那个）：**[lion-models1 @ ModelScope](https://modelscope.cn/models/lionnezha/lion-models)**
—— 9B、Qwen3.5 架构、按 Agent 工具调用微调，三档 GGUF 量化（Q8_0 / Q4_K_M / IQ4_XS）。
首次使用自动下载，装完不用配环境。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)

---

## 先说清楚这个项目不吹什么

旧的 README 里写着"超越某某"、"HumanEval 70% vs 50%"这类话。**那些删了** ——
没法验证的对比没有信息量。下面每一条都能在仓库里找到对应的代码或用例；
**做不到的事写在最后的「已知限制」里**，而不是藏起来。

## 装什么（就一个包）

`LionBox-Setup-1.5.4.exe`（74 MB）= 后端 + WebUI + llama.cpp 运行时（Vulkan）+ 精简 JRE +
内置技能 + **VS Code 插件**。装完：

- **VS Code 右侧栏**（secondary sidebar）多出 **LionBox Agent** 面板 —— 不用跳出去，
  在编辑器里就能让 Agent 干活；卸载时插件跟着卸掉；
- 想用完整界面：开始菜单启动 LionBox，浏览器打开 `http://127.0.0.1:8080`；
- 首次用到本地模型时自动从 ModelScope 下载权重（默认 Q8_0，8.87 GB，**只下一次**）；
  离线部署就把 `.gguf` 放进安装目录，程序优先用本地文件。

## 在编辑器里怎么干活

| 能力 | 说明 |
| --- | --- |
| 工作区 = 打开的文件夹 | 插件把当前文件夹交给后端建工作区；换文件夹就换工作区、自动开新会话 |
| `@` 引用单个文件 | 只把这一个文件读进上下文（窗口默认 16K，省着用）；也支持 `@history:` 引用历史对话 |
| **AI 改的文件要你先点通过** | 改文件的工具先攒成待审改动，面板里看 diff，点「通过并写入」才落盘；点「打回」写理由，模型按理由重写 —— 相当于作业签字 |
| 暂停 / 继续 / 停止 | 长任务跑到一半可以叫停 |
| 看得见每一步 | 对话里实时显示正在调用的工具名和结果 |

## 一切皆插件（71 个 / 9 类）

运行时核心保持薄，能力都挂在插件上：**能热插拔、能在设置里逐项开关、能自己写**。
（下面的数字取自运行中的 `/api/plugins`，不是手写的。）

| 类别 | 数量 | 干什么 |
| --- | --- | --- |
| BASE_TOOL | 22 | 极简模式下可用的基础工具：读写文件、列目录、跑命令、搜内容 |
| ADVANCED_TOOL | 36 | 标准模式追加：Git、网络、代码与文本处理等 |
| SKILL | 4 | 技能包（通用 skill 格式：介绍 + 何时用 + 正文），可自己放进 `skills/` |
| TERMINAL | 1 | 常驻终端：每条命令最长跑多久、最多回多少内容，用户可配 |
| AGENT_LOOP | 1 | 大循环：最多几轮、单工具等多久、一轮派几个工具 |
| SUBAGENT | 2 | 子智能体：递归层级、数量上限、用哪个模型 |
| AGENT_TEAM | 2 | 智能体团队：每个成员用什么模式、负责什么 |
| APPROVAL_REVIEW | 2 | 自动授权审查（让另一个模型先判该不该放行）+ **改动人工审核** |
| AUTOMATION | 1 | 自动化任务：按时间或周期在会话里自动执行 |

设置里只有一个「插件管理」页：每类插件的开关 + **该插件自己的参数就在它那一行下面**，
还有插件开发模式（生成骨架 → 改代码 → 重新加载，不用重启）。

## 上下文是钱，所以默认 16K

本机模型原生 256K，但**每一条消息都要把整个前缀重新预填充一遍** —— 窗口开多大就多算多少。
所以默认窗口收到 **16K**，另外给模型两个工具自己管：

- `context_window` —— 真的不够时调大，**干完必须调回来**（约束写在系统提示里）；
- `context_prune` —— 前面那些探查过程没用了，自己删掉（真删，保留最近几条）。

两者都按会话记，界面上也能手动改（输入框下面那个「窗口 16K」）。

## 验证到什么程度

- **39 个回归套件，0 失败**（`python tools/checks/_run_all_checks.py`）：工具调用、上下文、
  插件系统、会话、UI/接口、技能、`@` 引用、终端限制、压缩与裁剪……
- **真装真卸**：安装包装到临时目录 → 用装出来的自带 JRE 起服务 → 断言页面/接口 → 卸载
  （`tools/release/_verify_154_single.py`，其中包含"VS Code 里到底有没有装上插件"这一条）；
- 插件系统的行为用例是"真跑"：派子智能体、团队分头干活、审查 DENY/ALLOW、自动化到点投递、
  一轮最多几个工具、改动通过/打回 —— 不是看代码里有没有函数名。

## 已知限制

- **本机 4bit 模型的工具选择准确率有限**：实测"第一次就选对工具"约 43% → 66.7%；
  harness 保证的是**调用格式合法（30/30）、选错了能纠正、多轮下来最终选对 93.3%**。
  要更高就得换更大的权重或云端模型。
- **慢**：Q8 权重 + Intel Arc 核显约 **10.9 token/s**，一条复杂任务几分钟很正常。
- **没有代码签名**：Windows SmartScreen 首次运行会拦一下（"更多信息 → 仍要运行"）。
- **Windows 为主**：安装包、常驻终端、VS Code 插件都是按 Windows 验证的。
- **模型权重不随包发布**（8.87 GB），首次使用要联网下载。
- 自动装 VS Code 插件认几个常见安装位置；装在别处就手动装（VSIX 在安装目录里）。

## 配套模型

| | |
| --- | --- |
| 模型 | **lion-models1**（9B，Qwen3.5 架构，32 层混合线性注意力） |
| 地址 | **https://modelscope.cn/models/lionnezha/lion-models** |
| 量化 | Q8_0 8.87 GB（推荐）/ Q4_K_M 5.24 GB / IQ4_XS 4.87 GB |
| 怎么来 | Qwen3.5-9B 底座 + 4.9 万条 Agent 工具调用数据 QLoRA 微调（核显笔记本上做的），合并后量化 |
| 换别的 | 也支持任意 OpenAI 兼容端点（本地或云端），设置 → 模型来源 里切 |

这个模型和 harness 是**配套设计**的：模型按文本工具调用约定微调，harness 两种通道都认；
系统提示里教的"一轮可以给多个互不依赖的调用、干完活要收尾"，也是 harness 真正支持的语义。
模型卡片（下载、校验、llama.cpp 参数、速度）见仓库里的 [`README-模型.md`](README-模型.md)。

## 自己编

```bash
# 环境：Java 21+、Maven 3.9+
mvn -o -q -DskipTests package         # 产出 fat jar（约 37 MB）
java -jar target/lion-code-agent-harness-1.0.0-SNAPSHOT.jar
# 打开 http://localhost:8080
```

出安装包（需要 Inno Setup 6）：

```bash
python tools/release/_finalize_154.py     # 编 jar → 打 VSIX → 打安装包 → 清旧产物
```

## 技术栈与项目结构

Java 21 / Spring Boot 3.5 后端 + 单文件前端（`web/index.html`，无构建步骤）；
本地推理走 llama.cpp（`llama-server`，OpenAI 兼容，端口 8788）；
VS Code 插件是一个 webview 视图（`extensions/vscode/`）。

```
src/main/java/com/lioncode/
├── core/agent/          Agent 主循环、上下文预算与裁剪、工具别名、SPI
├── core/agent/change/   改动人工审核（待审队列 + 逐行 diff）
├── core/plugin/         插件系统（9 类）：tool/ skill/ team/ review/ automation/ change/
├── core/session/        会话与对话历史
├── core/workspace/      工作区
├── model/adapter/       OpenAI 兼容 / Anthropic 适配器
├── web/controller/      REST 接口
└── LionCodeApplication.java
web/index.html           前端（对话、插件管理、设置）
extensions/vscode/       VS Code 插件（右侧栏 Agent 面板）
skills/                  内置技能（通用 skill 格式）
installer/               安装脚本（Inno Setup）
tools/checks/            39 个回归套件
```

## 主要接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat` | 发消息（Agent 干活） |
| GET | `/api/plugins` | 插件清单（含类别与开关状态） |
| POST | `/api/plugins/{id}/enable` \| `/disable` | 单个插件热插拔 |
| GET/POST | `/api/context` | 看 / 改上下文窗口 |
| GET | `/api/changes` | 待人工审核的改动 |
| POST | `/api/changes/{id}/approve` \| `/reject` | 通过（落盘）/ 打回（带理由） |
| GET | `/api/skills` | 技能清单 |
| GET | `/api/sessions/{id}/history` | 会话历史 |

## 许可

[MIT](LICENSE)。

## 致谢

- [llama.cpp](https://github.com/ggml-org/llama.cpp) —— 本地推理运行时
- [Qwen](https://github.com/QwenLM/Qwen) —— 底座模型架构
- [DeepSeek-Harness](https://github.com/deepseek-ai/dsh)、[Claude Code](https://github.com/anthropics/claude-code)
  —— Agent 循环、工具调用与事件溯源的思路参考
