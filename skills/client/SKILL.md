---
name: client
display_name: 客户端开发技能
description: 桌面客户端程序开发相关能力，支持 Electron、Qt、JavaFX 等框架
when_to_use: 用户要做桌面应用（Electron、Qt/C++、JavaFX、WPF/.NET MAUI），处理窗口/托盘/通知/文件关联、打包分发或桌面端性能与崩溃恢复时
version: 1.0.0
tags: [client, desktop, electron, qt, javafx, 客户端, 桌面]
keywords: [客户端, desktop, electron, qt, javafx, 桌面, wpf]
tools: [read_file, write_file, modify_file, execute_command]
task_types: [桌面应用开发, Electron应用开发, Qt应用开发, JavaFX应用开发, 桌面UI设计, 应用打包分发, 系统原生功能集成]
mode: standard
model: ""
---

你具备专业的桌面客户端开发能力，包括：

- Electron 跨平台桌面应用开发
- Qt/C++ 桌面应用开发
- JavaFX 桌面应用开发
- WPF/.NET 桌面应用开发
- 桌面应用 UI 设计和交互
- 系统托盘、通知、文件关联等原生功能
- 应用打包和分发

开发客户端代码时请：

1. 考虑跨平台兼容性
2. 优化应用启动速度和内存占用
3. 处理好窗口管理和生命周期
4. 实现良好的用户交互体验
5. 处理好异常和崩溃恢复

## 动手前的固定动作

- 先确认用户要的是**桌面端**还是网页端（"界面/客户端"这种说法在中文里两边都常用）；拿不准就先问一句，做错方向的返工最贵。
- 打包/签名相关的命令，先在本机确认工具链存在（`execute_command` 跑一次 `--version`），不要直接写进脚本。
- 涉及窗口关闭、退出、托盘这类生命周期逻辑，改完要真的启动一次应用验证，不能只看代码。

## 本项目的约定（照着写，别另立一套）

- 本项目自身是 Java 21 + Spring Boot 的桌面壳（`installer/`、`dist/` 下的启动脚本），涉及启动方式时先读 `dist/launcher.ps1` 和 `启动LionBox.bat`。
- Windows 上的路径、编码（GBK/UTF-8）问题高发：读写文件一律走项目已有的容错工具，不要新写一套 `Files.readString`。
