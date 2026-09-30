---
name: frontend
display_name: 前端开发技能
description: 网页前端编写、样式调试、组件开发、接口对接等前端相关能力
when_to_use: 用户要写/改网页界面（HTML/CSS/JS/TS）、调样式或响应式布局、做组件、对接后端接口、处理浏览器兼容与前端性能时
version: 1.0.0
tags: [frontend, react, vue, angular, html, css, js, 前端]
keywords: [前端, frontend, html, css, javascript, react, vue, 页面, 样式]
tools: [read_file, write_file, modify_file, search_in_files, execute_command]
task_types: [前端代码编写, 组件开发, 样式调试, 响应式布局, 前后端对接, 前端性能优化, 浏览器兼容]
mode: standard
model: ""
---

你具备专业的前端开发能力，包括：

- 网页前端代码编写（HTML、CSS、JavaScript、TypeScript）
- 主流框架开发（React、Vue、Angular）
- 组件设计和开发
- 样式调试和响应式布局
- 前后端接口对接
- 前端性能优化
- 浏览器兼容性处理

开发前端代码时请：

1. 使用语义化 HTML 标签
2. CSS 使用现代布局方式（Flexbox、Grid）
3. 组件保持单一职责
4. 注意可访问性（a11y）
5. 优化加载性能

## 动手前的固定动作

- 改界面前先 `read_file` 读现有的 HTML/CSS/JS，沿用已有的类名、配色和交互写法，别引入第二套风格。
- 前端调接口前，先确认后端真的返回这些字段（去读对应 Controller，或 `search_in_files` 找端点路径），不要按想象拼字段名。
- 改完在浏览器里刷一遍看效果；只改了字符串没验证渲染，等于没改。

## 本项目的约定（照着写，别另立一套）

- LionBox 的前端是**单文件** `web/index.html`：样式、结构、脚本都在里面，改动要跟着现有分节注释走。
- 所有交互文案用中文，和现有界面保持一致。
- 调后端接口统一走 `/api/...`，响应外层是 `{success, message, data, error}`（个别新端点会同时带 `ok` 字段）。
