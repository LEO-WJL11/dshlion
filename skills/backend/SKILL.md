---
name: backend
display_name: 后端开发技能
description: 后端代码编写、调试、编译排错、接口设计、依赖处理等后端相关能力
when_to_use: 用户要写/改/调试服务端代码（Java、Python、Go、Node.js），设计 REST/GraphQL 接口，动数据库或优化 SQL，排查编译/依赖错误，补单元测试时
version: 1.0.0
tags: [backend, java, python, go, node, api, 后端, 服务端]
keywords: [后端, backend, api, java, python, 服务端, 数据库, 接口, 编译]
tools: [read_file, write_file, modify_file, search_in_files, execute_command, git_status, git_commit]
task_types: [后端代码编写, API接口设计, 数据库设计, 编译错误修复, 依赖管理, 性能优化, 代码重构, 单元测试编写]
mode: standard
model: ""
---

你具备专业的后端开发能力，包括：

- 后端代码编写和重构（Java、Python、Go、Node.js 等）
- RESTful API 和 GraphQL 接口设计
- 数据库设计和 SQL 优化
- 依赖管理和构建工具使用（Maven、Gradle、npm 等）
- 编译错误排查和修复
- 性能优化和代码审查
- 单元测试和集成测试编写

开发后端代码时请：

1. 遵循语言和框架的最佳实践
2. 编写清晰的错误处理逻辑
3. 添加必要的注释和文档
4. 考虑安全性和性能
5. 编写可测试的代码

## 动手前的固定动作

- 改代码前先 `read_file` 看真实实现，不要凭类名猜（这个项目里同名/近似名的类很多）。
- 改完必须编译验证：本项目用 `& .\tools\dev\_mvn.ps1 -o -q -DskipTests compile`（PATH 里的 `mvn` 是 npm 假货，别直接用）。
- 接口改动要同时看调用方（前端 `web/index.html`、其它 controller），只改一半等于没改。

## 本项目的约定（照着写，别另立一套）

- 所有注释用中文，注释解释**为什么**这么写，而不是复述代码在做什么。
- 工具类返回错误时给"能照着改"的提示（缺哪个参数、该填什么），不要只回一句"失败"。
- 时间/金额/路径这类易错参数，工具里要容错（字符串数字、"5 行"这种写法都要认）。
