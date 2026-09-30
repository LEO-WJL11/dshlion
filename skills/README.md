# 技能目录（SKILL.md）

这个目录放的是 **LionBox 的内置技能**，随安装包一起发布。每个技能一个子目录，
目录名就是技能 id：

```
skills/
  backend/SKILL.md      # id = backend
  client/SKILL.md
  document/SKILL.md
  frontend/SKILL.md
```

用户自己的技能放 `<用户主目录>/.lioncode/skills/<id>/SKILL.md`（和 `app-config.json` 同一个目录）。
**同 id 时用户技能覆盖内置技能**，所以用户想改内置技能的行为，不用动安装目录。

## 文件格式

YAML frontmatter + Markdown 正文。正文就是"给模型看的操作说明"：

```markdown
---
name: pdf                                  # 唯一 id（必填，和目录名一致最好）
display_name: PDF 处理                      # 界面上显示的名字（必填）
description: 一句话说明这个技能能干什么        # 必填，模型就是靠这句话判断要不要用
when_to_use: 什么情况下该用这个技能           # 必填，给模型看的触发条件
version: 1.0.0                             # 可选
tags: [pdf, document]                      # 可选，分类用
keywords: [pdf, 合并, 拆分]                 # 可选，关键词匹配兜底
tools: [read_file, write_file]             # 可选，本技能要用到的工具名
task_types: [合并PDF, 拆分PDF]              # 可选，任务类型描述
mode: standard                             # 可选：minimal / standard / any
model: ""                                  # 可选，指定跑这个技能的模型（留空=用当前模型）
---

正文：具体怎么写、注意什么、示例。
```

## 模型怎么用上它

1. LionBox 会把**技能目录**（每个启用技能一行：id — 名字：说明（什么时候用））注入系统提示词；
2. 模型判断某个技能匹配当前任务时，先调 `skill_load` 工具（参数 `name`）拿到完整正文，再动手；
3. 用户也可以直接指定：消息里写 `@skill:pdf` 或 `/skill:pdf`（正文直接展开进这一轮），
   或者调 `POST /api/skills/active` 给整个会话钉住技能。

## 写坏了的技能会怎样

frontmatter 缺必填字段、YAML 语法错、正文空的技能**不会让应用起不来**：
它照样出现在 `GET /api/skills` 的列表里，只是 `error` 字段写明原因、`enabled=false` 不参与匹配。
改好文件后调 `POST /api/skills/reload` 即可，不用重启。
