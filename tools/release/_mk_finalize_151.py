#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""从 _finalize_150.py 生成 _finalize_151.py。

只改三件事：版本号、自测记录轮次（39 → 40，内容换成 1.5.1 这一轮）、
以及包内反查新增一项（maxToolsPerRound）。
为什么用生成而不是手抄：这脚本 300 多行，手抄必错，下次发版还得再抄一遍。
"""

import io
import os

HERE = os.path.dirname(os.path.abspath(__file__))
src_path = os.path.join(HERE, '_finalize_150.py')
dst_path = os.path.join(HERE, '_finalize_151.py')

src = io.open(src_path, encoding='utf-8', newline='').read()

src = src.replace("VER = '1.5.0'", "VER = '1.5.1'")
src = src.replace('1.5.0 定稿完成', '1.5.1 定稿完成')
src = src.replace('1.0.0 前置检查', '1.5.1 前置检查')
src = src.replace("u'第 39 轮' in t2", "u'第 40 轮' in t2")
src = src.replace("print('自测记录里已有第 39 轮，跳过')", "print('自测记录里已有第 40 轮，跳过')")
src = src.replace('自测记录已追加第 39 轮', '自测记录已追加第 40 轮')
src = src.replace("    ('技能端点', 'core/plugin/skill/SkillController.class'),\n",
                  "    ('技能端点', 'core/plugin/skill/SkillController.class'),\n"
                  "    ('大循环：一轮最多几个工具', 'maxToolsPerRound'),\n")

NEW_ROUND = '''    add = u\'\'\'

## 2026-10-01 · 第 40 轮：1.5.1（九类插件"真能干活"核对 + 补齐界面入口）

### 136. 起因

用户问"前面我说那些插件你怎么做的，符不符合要求"。对的做法不是拿"代码里有这个名字"当结论，
而是把每条要求**真跑一遍**。于是新写了 tools/checks/_check_plugin_extras.py（20 条断言），
用假模型把九类插件里"真干活"的那几类串起来跑：派子智能体、层级/并发上限、团队分头干活、
审查 DENY/ALLOW、自动化到点投递、大循环轮次上限、终端输出与超时、一轮最多几个工具、整组开关。

### 137. 真跑之后发现并补掉的四个缺口

| 缺口 | 原来什么样 | 现在 |
|---|---|---|
| 参数没有界面入口 | 终端/大循环/子智能体/审查的参数**只有后端接口**，用户改不了 | 新增「设置 → 插件参数」页签（含团队增删、自动化任务增删） |
| "提供商"配了没用 | 审查插件只读 model，永远拿主 Agent 的适配器去问 | 按 provider 取对应适配器，取不到才退回当前适配器 |
| "控制派发方式"缺一项 | 一轮派几个工具写死，而且那段**是死代码**（没有任何调用点） | maxToolsPerRound 可配（0=不限，保持"给多少执行多少"）；超出上限的调用按原顺序回"未执行、下一轮继续" |
| 一整类插件没法一起关 | 只能一个个点（基础工具 22 个） | POST /api/plugins/kind/{kind}/enable 或 /disable + 每组"全开/全关" |

### 138. 功能回归（20 条断言，全过）

- agent_spawn 真派活：子 Agent 开了独立会话、结论带回主 Agent；
- maxDepth=0 / maxConcurrency=0 时派发被拒，而且拒绝话回到模型（不是抛异常）；
- agent_team_run：两个成员都真跑了、结论都汇总回来；
- 授权审查：DENY 时工具**没有执行**、ALLOW 时正常执行；
- 自动化：到点消息真的进了会话，投递后记账、不重复触发；
- 大循环：maxIterations=2 到点停并说明原因；maxToolsPerRound=1 时多出来的调用被告知"未执行"；
- 终端：3 秒超时强杀重启、300 字节截断并注明；
- 整组开关：34 个进阶工具一起关，提示词里 web_search 立刻消失；再全开都回来。

### 139. 出包 1.5.1

installer\\\\release\\\\LionBox-Setup-1.5.1.exe  SIZE_PLACEHOLDER B（SIZEMB MB）

要求逐条对照表见 docs\\\\插件要求对照.md，里面有**三处"形态和字面不一样"**的说明：
基础/进阶工具是按模式自动分类、不是两个大插件；子智能体/团队是"工具 + 配置"的组合、
派活走主循环链路（串行可停可审计）；自动化最小周期 5 秒、轮询 15 秒。
\'\'\'
'''

start = src.index("    add = u'''")
end = src.index("'''\n", start + len("    add = u'''")) + len("'''\n")
src = src[:start] + NEW_ROUND + src[end:]

io.open(dst_path, 'w', encoding='utf-8', newline='').write(src)
print('已生成 %s（VER=1.5.1，自测第 40 轮）' % dst_path)
