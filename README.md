# 🦁 Lion-Code Agent Harness

**本地 AI 编程 Agent 运行时框架** — 一切皆插件，Agent 通过调用工具真实操作文件、执行命令、完成任务。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green.svg)](https://spring.io/projects/spring-boot)

---

## ✨ 核心特性

- 🧩 **一切皆插件** — 57 个插件（4 Skill + 53 Tool），核心 runtime 保持轻量，工具/技能可热注册
- 🔧 **原生 Function Calling** — 自动向模型 API 发送工具定义，模型直接返回结构化工具调用；同时兼容 XML/JSON 文本格式工具调用解析
- ⛔ **单工具轮次** — 强制一次只执行一个工具，模型一次返回多个调用时自动裁剪并纠正
- ⏸️ **暂停 / 继续 / 停止** — 任务运行期间可随时暂停（下一轮前生效）、继续或优雅停止
- 👁️ **对话中实时显示工具名** — 前端轮询事件流，实时展示 `🔧 调用工具：read_file` 等轨迹
- 📁 **工作区真实绑定** — 工具相对路径基于会话绑定工作区解析，Shell 默认在工作区内执行
- 🧠 **思考等级** — 支持 DeepSeek/MiMo/Claude 等模型的思考等级选择，thinking 模式 `reasoning_content` 全链路回传
- 📝 **事件溯源** — 完整记录每轮思考、工具调用、返回结果，支持回放调试
- 🔄 **双协议适配器** — OpenAI 兼容 + Anthropic Claude 原生，支持热切换
- 🎯 **四种工作模式** — PTC / 创造 / 标准 / 极简
- 🔒 **三级权限** — 只读 / 工作区写 / 全部权限
- 🏪 **20+ 服务商模板** — 内置国内外主流 API 提供商配置，填入 API Key 即用

## 🚀 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | 运行时必需 |
| Maven | 3.9+ | 仅从源码构建时需要 |
| 浏览器 | 现代浏览器 | 访问 Web UI |

### 方式一：直接使用发行 Jar（推荐）

仓库根目录自带构建好的 fat jar，Windows 双击 `启动.bat`，或命令行：

```bash
java -jar lion-code-agent-harness-1.0.0-SNAPSHOT.jar
```

启动后浏览器访问 **http://localhost:8080**。

### 方式二：从源码构建

```bash
git clone git@github.com:LEO-WJL11/dshlion.git
cd dshlion
mvn -DskipTests package
java -jar target/lion-code-agent-harness-1.0.0-SNAPSHOT.jar
```

> 提示：Windows 下 Maven 命令若提示找不到，请确认已安装 Maven 并加入 PATH。

### 配置 API 提供商（首次使用必做）

1. 打开页面右上角 **⚙️ 设置** → 选择服务商（如 DeepSeek、小米 MiMo、阿里百炼……）
2. 填入 **API Key**，点击保存
3. 系统自动调用该服务商 `/models` 接口拉取**真实模型列表**，下拉框选择模型
4. 选择**思考等级**（thinking 模型才有，如 DeepSeek V4 系列）
5. 开始对话

> API Key 仅保存在服务端内存中，重启后需重新填写，**不会写入任何文件**。

详细使用说明见 **[使用教程](使用教程.md)**。

## 📦 插件清单

### 4 个 Skill 技能包

| 技能 | 说明 |
|------|------|
| 📚 文档读写技能 | 撰写、解析、格式转换 |
| 🖥️ 后端开发技能 | 后端代码、调试、编译排错 |
| 🎨 前端开发技能 | 前端编写、样式、组件开发 |
| 💻 客户端开发技能 | 桌面客户端程序开发 |

### 53 个 Tool 工具插件

| 类别 | 数量 | 说明 |
|------|------|------|
| 📁 文件操作 | 18 | read / write / modify / append / delete / copy / move / list / info / mkdir / touch / headtail / wc / linecount / tree / chmod 等 |
| 🔍 代码工具 | 14 | 格式化 / JSON / YAML / 正则 / Base64 / 哈希 / 时间戳 / 字符串处理等 |
| 🌿 Git | 8 | status / commit / log / diff / branch / stash / remote / init |
| 🌐 网络 | 7 | web_search / http_get / http_post / fetch / download / translate / dns |
| 💻 Shell | 3 | execute_command / run_background / stop_background |
| ⚙️ 系统 | 3 | system_info / get_env / working_directory |

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────┐
│                 Web UI (8080)                │
├─────────────────────────────────────────────┤
│              REST API Controllers            │
├──────┬──────┬──────┬──────┬──────┬──────────┤
│ Agent│Event │Plugin│Model │Session│ Workspace│
│ Loop │Store │System│Adapt │ Mgr   │   Mgr    │
├──────┴──────┴──────┴──────┴──────┴──────────┤
│    OpenAI适配器    │   Anthropic适配器        │
├────────────────────┴────────────────────────┤
│              插件注册表 (57个)                │
│  ┌─────────┐  ┌──────────────────────────┐  │
│  │4个Skill │  │      53个Tool插件         │  │
│  └─────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────┘
```

## 🔧 主要 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 发送消息（同步，含完整工具调用循环） |
| POST | `/api/chat/stream` | 发送消息（SSE 流式） |
| POST | `/api/chat/control/pause` | 暂停当前任务 |
| POST | `/api/chat/control/resume` | 继续当前任务 |
| POST | `/api/chat/control/stop` | 停止当前任务 |
| GET | `/api/chat/control/status` | 查询任务控制状态 |
| GET | `/api/events/{sessionId}?after=` | 查询会话事件（工具调用轨迹） |
| GET | `/api/plugins` | 插件列表 |
| GET/POST | `/api/sessions` | 会话列表 / 创建 |
| DELETE | `/api/sessions/{sessionId}` | 销毁会话 |
| GET | `/api/models` | 当前适配器模型列表 |
| GET | `/api/models/thinking-levels?modelId=` | 模型思考等级 |
| GET | `/api/providers/templates` | 提供商模板 |
| POST | `/api/providers/{id}/models` | 实时拉取提供商模型列表 |
| POST | `/api/workspaces` | 注册工作区 |
| GET | `/api/workspaces/default` | 默认工作区（动态计算） |
| GET | `/api/workspaces/common` | 常用路径列表（动态计算） |

## 📁 项目结构

```
dshlion/
├── src/main/java/com/lioncode/
│   ├── LionCodeApplication.java          # 主启动类
│   ├── core/
│   │   ├── agent/                        # Agent主循环、思考等级、运行控制(暂停/停止)
│   │   ├── event/                        # 事件溯源存储
│   │   ├── plugin/                       # 插件系统（注册表/加载器/热插拔）
│   │   │   ├── skill/                    # 4个Skill技能包
│   │   │   └── tool/                     # 53个Tool工具插件
│   │   ├── session/                      # 会话管理与历史持久化
│   │   └── workspace/                    # 工作区管理与线程级工作区上下文
│   ├── model/
│   │   ├── adapter/                      # OpenAI兼容 + Anthropic双协议适配器
│   │   └── config/                       # 20+提供商配置模板
│   ├── web/                              # REST控制器与DTO
│   ├── mcp/                              # MCP协议桥接
│   ├── queue/                            # 消息队列
│   └── approval/                         # 审批策略
├── web/                                  # 前端页面（单文件HTML + JS模块）
├── lion-code-agent-harness-1.0.0-SNAPSHOT.jar   # 发行fat jar
├── 启动.bat                               # Windows一键启动脚本
├── pom.xml                               # Maven配置
├── 使用教程.md                            # 详细使用教程
├── LICENSE                               # MIT许可证
└── README.md                             # 本文件
```

## 🛠️ 技术栈

- **后端**: Java 21, Spring Boot 3.5.0, OkHttp, Jackson, Reactor (WebFlux)
- **前端**: 原生 HTML / CSS / JavaScript（无构建步骤）
- **构建**: Maven 3.9+
- **协议**: OpenAI Function Calling, Anthropic Messages API, SSE 流式

## 🔐 安全说明

- API Key 仅保存在内存，不落盘、不入库、不写日志
- 工具执行强制绑定工作区，三级权限控制（只读 / 工作区写 / 全部权限）
- 事件溯源文件默认存放于 `~/lion-code-workspace/.lioncode/events`，可配置

## 📄 许可证

本项目采用 [MIT 许可证](LICENSE) 开源。

## 🙏 致谢

- [DeepSeek-Harness](https://github.com/deepseek-ai/dsh) — 架构设计理念参考
- [Claude Code](https://github.com/anthropics/claude-code) — 工具调用与事件溯源参考
- [OpenAI](https://openai.com) — Function Calling 协议设计参考
