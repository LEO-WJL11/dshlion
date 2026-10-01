#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""重写两个 README（项目 / 模型），让它们互相介绍对方，并对到 1.5.4 的真实功能。

【用户的要求】"把那两个 README 改了，有点不现实" + "他们两个互相介绍对方：自我介绍的同时提一嘴对方"
+ "同步成现在的这些功能"。

【"不现实"具体指什么（旧 README 的问题）】
  · 项目 README 写"超越 DeepSeek-Harness"、HumanEval 70% vs 50% 的拉踩对比 —— 这种话没法验证；
  · 插件数写死"58 个插件（4 Skill + 54 Tool）"，实际是 71 个 / 9 类 / 58 个工具（实测）；
  · 部署说明还是 "git clone your-username/lion-code" 这种占位符，也没有 VS Code 插件、
    一个安装包、改动人工审核、16K 上下文这些**现在就有的东西**；
  · 模型 README 写"⛔ 单工具轮次：强制一次只执行一个工具" —— 现在的默认恰恰相反
    （一轮给几个就执行几个，上限可配）。
两个文件都改成：能验证的说法 + 互相指路 + 已知限制单独一节。
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

MODEL_README = '''# lion-models1

**一个能真正动手干活的 9B 编程 Agent 模型** —— 读文件、改代码、跑命令、看结果、接着干，
而不是只吐一段代码让你复制。

- **9B 规模**，Qwen3.5 架构（32 层，混合线性注意力），**完全本地运行**：权重在你自己机器上，代码和数据不出本机
- **按 Agent 工具调用训练**：会正确调工具、不该调的时候不调、干完活会收尾
- 自我认知是 `lion-models1`（由 Lion 开发），不冒充 Qwen / GPT
- 配套运行时：**[LionBox / Lion-Code Agent Harness](https://github.com/LEO-WJL11/dshlion)** ——
  一个安装包装完即可对话，界面是 VS Code 右侧栏 + 浏览器 WebUI

> **两个项目是一套东西的两半**：这里是模型（"脑子"），那边是运行时（"手脚 + 规矩"）。
> 模型负责决定下一步做什么，运行时负责真的去做、并把每一步摊开给你看、
> 改文件之前还会停下来等你点「通过」。

---

## 下载

仓库：**<https://modelscope.cn/models/lionnezha/lion-models>**

| 文件 | 大小 | 说明 |
|---|---|---|
| `lion-merged-Q8_0.gguf` | **8.87 GB** | **推荐**，质量最好；32 GB 内存的机器用它 |
| `lion-merged-Q4_K_M.gguf` | 5.24 GB | 体积与质量平衡 |
| `lion-merged-IQ4_XS.gguf` | 4.87 GB | 最小，内存/显存紧张的机器用 |

命令行下载：

```bash
modelscope download --model lionnezha/lion-models lion-merged-Q8_0.gguf --local_dir .
```

**Q8 权重校验**（下完建议对一下）：

```
文件大小 9,527,501,408 字节
sha256   d154382c0e2b8d2e24d7a449713196249a824f07cead45bd31aeeec3dffb632f
```

> **用 LionBox 的话不用手动下**：首次用到本地模型时自动从上面的仓库拉取（只下一次，
> 界面能看到进度）。离线/内网：把 `.gguf` 放进程序目录（或 `models\\` 子目录）即可，
> 程序优先用本地现成的文件。

---

## 怎么跑

需要**支持 `qwen35` 架构的 llama.cpp**（b7xxx 之后）。老版本会直接加载失败。

```bash
llama-server.exe -m lion-merged-Q8_0.gguf \\
  -c 262144 --flash-attn \\
  --cache-type-k q4_0 --cache-type-v q4_0 \\
  --temp 0.2 --top-p 0.9 --repeat-penalty 1.05
```

- 服务端点：**`http://127.0.0.1:8788/v1`**（OpenAI 兼容，可以当普通 API 直接接）
- 上下文：原生 **256K**（GGUF 里 `context_length=262144`），不需要 RoPE 外推

**实测速度**（Q8 权重 + Intel Arc 核显，共享内存）：约 **10.9 token/s** 生成；
纯 CPU（16 核）预期 8–12 token/s（区间估计）。

**内存建议**：256K 上下文 + Q8 权重建议 **32 GB**。内存不够时**先把上下文调小**
（`-c 65536` 或 `32768` 足够写代码），比换小权重更划算。

> 一个实用的搭配：LionBox 默认只给模型 **16K 上下文**（因为每轮都要重新预填充整个前缀，
> 窗口越大越慢），需要时它自己会调大、干完再调回来。所以 `-c 262144` 开着但日常只用一小截，
> 是刻意的用法。

---

## 配合 LionBox（原 dshlion）用

模型要有循环、工具、权限和审核才叫 Agent：

> ### 👉 https://github.com/LEO-WJL11/dshlion

**LionBox** —— 本地 AI 编程 Agent 运行时（Java 21 / Spring Boot），
"一切皆插件"：**71 个插件、9 类**，全部可在设置里开关、可热插拔：

| 类别 | 数量 | 干什么 |
|---|---|---|
| 基础工具 | 22 | 读写文件、列目录、跑命令、搜内容（极简模式只用这些） |
| 进阶工具 | 36 | Git、网络、代码与文本处理（标准模式追加） |
| 技能 | 4 | 通用 skill 格式的技能包，用户可自己放进 `skills/` |
| 终端 | 1 | 常驻 shell：每条命令最长跑多久、最多回多少内容，用户可配 |
| 大循环 | 1 | 最多几轮、单工具等多久、一轮派几个工具 |
| 子智能体 | 2 | 递归层级、数量上限、用哪个模型 |
| 智能体团队 | 2 | 每个成员用什么模式、负责什么 |
| 授权审查 | 2 | 另一个模型先判该不该放行 + **改动人工审核** |
| 自动化任务 | 1 | 按时间或周期在会话里自动执行 |

跟这个模型直接相关的几条：

- **两条工具调用通道都支持**：原生 function calling，以及文本 `<tool_call>` 解析
  （本模型是按文本约定微调的，运行时用文本通道最稳）；
- **一轮可以给多个互不依赖的调用**（默认不限制，可配上限）—— 训练时教的正是这个；
- **改动人工审核**：模型改的文件先攒成待审改动，你在 VS Code 右侧栏看到 diff，
  点「通过」才真正落盘，点「打回」它按你的理由重写；
- **上下文自己管**：`context_window`（不够时调大、干完调回）和 `context_prune`（删掉没用的历史）。

**想开箱即用**：下载 `LionBox-Setup-*.exe`（仓库 `installer/release/` 或 Releases），
装完打开 VS Code，右边最右那一栏就是它 —— 权重会自动下载，不需要配环境、不需要注册。

---

## 常见问题

**Q：加载失败，提示不支持的架构？**
llama.cpp 太老。换支持 `qwen35` 的版本（b7xxx 之后）。

**Q：加载很慢，或者内存爆了？**
先把上下文调小：`-c 65536`。256K 是上限不是必须，写代码 32K~64K 够用。

**Q：它说"我要调用工具"但什么都没做？**
工具调用的格式没对上。给它明确的工具定义，或者让运行时按文本 `<tool_call>` 约定解析
（LionBox 里可以直接切「原生 / 仅文本」）。

**Q：第一条消息特别慢？**
系统提示词 + 工具定义有 8–9K token 要先算一遍（预填充）。先发一条短消息、或触发一次预热，
之后就快了。

**Q：会不会把我的代码传出去？**
模型完全在你本机跑，推理过程不联网、不上传任何内容。只有你主动让助手联网搜索时才会出网。

**Q：它会不会自己乱改我的代码？**
默认不会：改文件的工具会把改动攒成待审，**你不点通过就不落盘**。想让它直接改，
可以在设置里关掉「改动人工审核」这个插件。

---

## 它是怎么来的（一句话）

拿 Qwen3.5-9B 做底座，在一台**核显笔记本**上做了 int4/fp8 QLoRA 微调
（4.9 万条 Agent 工具调用数据，其中 1.6 万条带真实工具调用），
把适配器合并回底座后用 `llama-quantize` 出上面那三档 GGUF —— 没有独显也能训出自己的 Agent 模型。
'''

PROJECT_README = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   '_readme_project_body.md'), encoding='utf-8').read() \
    if os.path.isfile(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   '_readme_project_body.md')) else None


def write(rel, text):
    p = os.path.join(ROOT, rel)
    io.open(p, 'w', encoding='utf-8', newline='').write(text.replace('\n', '\r\n'))
    print('已写 %s（%d 字符）' % (rel, len(text)))


def main():
    if PROJECT_README is None:
        print('缺少 _readme_project_body.md（项目 README 正文）')
        return 1
    write('README-模型.md', MODEL_README)
    write('dist/README-模型.md', MODEL_README)      # 安装包里那份保持一致
    write('README.md', PROJECT_README)
    print('两个 README 都更新了，并且互相指路')
    return 0


if __name__ == '__main__':
    sys.exit(main())
