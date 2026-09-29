# 🦁 Lion-Code Agent Harness

**本地Agent运行时框架** — 一切皆插件，超越DeepSeek-Harness

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-green.svg)](https://spring.io/projects/spring-boot)

---

## ✨ 核心特性

- 🧩 **一切皆插件** — 58个插件（4 Skill + 54 Tool，取自运行时 `/api/plugins`），核心runtime尽量薄
- ⚡ **流式工具执行** — 识别到工具调用块就立即调度执行
- 📝 **事件溯源** — 完整记录Agent每轮思考、工具调用、返回结果
- 🔄 **双协议适配器** — OpenAI兼容 + Anthropic Claude原生，支持热切换
- 🎯 **两种工作模式** — 标准模式 / 极简模式
- 🔒 **三级权限** — 只读 / 工作区写 / 全部权限
- 📊 **HumanEval评测** — pass@1 = 70%（MiMo v2.5）

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
│              插件注册表 (58个)                │
│  ┌─────────┐  ┌──────────────────────────┐  │
│  │4个Skill │  │      54个Tool插件         │  │
│  │技能包   │  │ 文件/Shell/Git/Web/代码   │  │
│  └─────────┘  └──────────────────────────┘  │
└─────────────────────────────────────────────┘
```

## 🚀 快速开始

### 环境要求

- Java 21+
- Maven 3.9+
- Python 3.x（用于评测脚本）

### 编译运行

```bash
# 克隆项目
git clone https://github.com/your-username/lion-code.git
cd lion-code

# 编译
mvn clean compile dependency:copy-dependencies -DoutputDirectory=target/dependency

# 启动
java -cp "target/classes;target/dependency/*" com.lioncode.LionCodeApplication
```

访问 http://localhost:8080

### 配置API

1. 打开设置 → API提供商
2. 选择服务商（如小米MiMo、DeepSeek、阿里百炼等）
3. 填入API Key
4. 选择模型开始对话

## 📦 插件清单

### 4个Skill技能包

| 技能 | 说明 |
|------|------|
| 📚 文档读写技能 | 撰写、解析、格式转换 |
| 🖥️ 后端开发技能 | 后端代码、调试、编译排错 |
| 🎨 前端开发技能 | 前端编写、样式、组件开发 |
| 💻 客户端开发技能 | 桌面客户端程序开发 |

### 54个Tool工具插件

| 类别 | 数量 | 工具 |
|------|------|------|
| 文件操作 | 15 | read, write, delete, copy, move, list, info, mkdir, touch, append, headtail, wc, linecount, tree, chmod |
| 文件搜索 | 2 | search, glob |
| 文件修改 | 1 | modify |
| Shell命令 | 3 | execute, background, stop |
| Git版本控制 | 7 | status, commit, log, diff, branch, stash, remote, init |
| 网络工具 | 7 | search, http_get, http_post, fetch, download, translate, dns |
| 系统工具 | 3 | info, env, cwd |
| 代码工具 | 14 | format, json, yaml, regex, base64, hash, diff, uuid, timestamp, cron, markdown, string, escape, number |

## 🧪 评测结果

### HumanEval（MiMo v2.5）

| Agent | pass@1 |
|-------|--------|
| 🦁 **Lion-Code** | **70.0%** |
| 🔷 DSH | 50.0% |

### 自定义编程题（LRU Cache）

| Agent | 功能覆盖 | 并发正确性 | 总分 |
|-------|---------|-----------|------|
| 🦁 **Lion-Code** | 8/8 | ✅ | **58/60** |
| 🔷 DSH | 8/8 | ❌ 有BUG | 45/60 |

## 🔧 API端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plugins` | 获取插件列表 |
| GET | `/api/sessions` | 获取会话列表 |
| POST | `/api/sessions` | 创建会话 |
| POST | `/api/chat` | 发送消息 |
| POST | `/api/chat/stream` | 流式消息 |
| GET | `/api/models` | 获取模型列表 |
| GET | `/api/models/thinking-levels` | 获取思考等级 |
| GET | `/api/providers/templates` | 获取提供商模板 |
| POST | `/api/workspaces` | 注册工作区 |

## 🛠️ 技术栈

- **后端**: Java 21, Spring Boot 3.5.0, OkHttp, Jackson, WebFlux
- **前端**: HTML/CSS/JavaScript（复用DSH配色风格）
- **构建**: Maven 3.9+
- **评测**: Node.js, Python

## 📁 项目结构

```
lion-code/
├── src/main/java/com/lioncode/
│   ├── LionCodeApplication.java          # 主启动类
│   ├── core/
│   │   ├── agent/                        # Agent主循环
│   │   ├── event/                        # 事件溯源存储
│   │   ├── plugin/                       # 插件系统
│   │   │   ├── skill/                    # 4个Skill技能包
│   │   │   └── tool/                     # 47个Tool工具插件
│   │   ├── session/                      # 会话管理
│   │   └── workspace/                    # 工作区管理
│   ├── model/
│   │   ├── adapter/                      # 双协议适配器
│   │   └── config/                       # 提供商配置
│   ├── web/                              # REST控制器
│   ├── mcp/                              # MCP协议桥接
│   ├── queue/                            # 消息队列
│   └── approval/                         # 审批策略
├── web/                                  # 前端页面
├── pom.xml                               # Maven配置
├── LICENSE                               # MIT许可证
└── README.md                             # 本文件
```

## 📄 许可证

本项目采用 [MIT许可证](LICENSE) 开源。

```
MIT License

Copyright (c) 2026 Lion-Code Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 🙏 致谢

- [DeepSeek-Harness](https://github.com/deepseek-ai/dsh) — 架构设计理念参考
- [Claude-Code](https://github.com/anthropics/claude-code) — 工具调用和事件溯源参考
- [HumanEval](https://github.com/openai/human-eval) — 编程能力评测数据集
