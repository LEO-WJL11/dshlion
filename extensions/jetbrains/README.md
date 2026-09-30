# LionBox for JetBrains IDEs

在 IDE 右侧 Tool Window 里用 **JCEF 内嵌浏览器**加载 LionBox（Lion-Code Agent Harness）
现有的本地 Web UI（默认 `http://127.0.0.1:8080`）。**不重写界面**。

```
extensions/jetbrains/
├── settings.gradle.kts
├── build.gradle.kts                     # org.jetbrains.intellij.platform 2.19.0
├── gradle.properties                    # 平台版本 2026.1 / since-build 261 / 发布令牌
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 9.8.0
└── src/main/
    ├── java/com/lioncode/jetbrains/
    │   ├── LionBoxToolWindowFactory.java      # ToolWindowFactory
    │   └── LionBoxPanel.java                  # JBCefBrowser + 健康轮询 + 重试
    └── resources/META-INF/plugin.xml          # toolWindow 扩展点 / depends / since-build
```

## 1. 版本来源（2026-09-30 核实）

| 组件 | 取值 | 来源 |
| --- | --- | --- |
| IntelliJ Platform Gradle Plugin | **2.19.0**（`org.jetbrains.intellij.platform`） | [官方文档](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)（页面日期 2026-07-21） |
| Gradle | **9.8.0**（2026-09-24 发布） | [Gradle Releases](https://gradle.org/releases/)；插件 2.x 要求 Gradle ≥ 9.0.0 |
| Java 运行要求 | ≥ 17 跑 Gradle；本插件字节码目标 **21** | 同上 Gradle 插件文档 "Requirements" |
| 目标平台 | **IntelliJ Platform 2026.1**（branch **261**，Java 21） | [Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html) |
| `since-build` | **261**，`until-build` 留空 | 同上 + [2026.2 公告](https://platform.jetbrains.com/t/2026-2-is-coming-time-to-check-your-plugin-compatibility/4618)（官方推荐直接删掉 untilBuild） |
| JCEF 依赖 | `<depends>com.intellij.modules.jcef</depends>` | 同上 2026.2 公告「JCEF Compatibility」：2026.2 起必须显式依赖；别名自 2025.3.1 起可用 |

> 为什么目标选 2026.1 而不是 2026.2：**2026.2 起 IntelliJ IDE 用 Java 25 构建**，
> 官方原话是"请永远不要用更新的 SDK 去构建面向旧版本发布的插件；用 2026.1 构建，运行时依然兼容 2026.2"。
> 若要显式面向 2026.2 构建，请把 `platformVersion` 改成 `2026.2`、`pluginSinceBuild` 改成 `262`，
> 并把 `build.gradle.kts` 里注释掉的 `bundledPlugin("intellij.platform.ui.jcef")` 打开（那时需 JDK 25）。

## 2. 构建（确切命令）

本机 **Maven 无关**，全部走 Gradle：

```powershell
cd C:\Users\Leo\Desktop\lion-code\extensions\jetbrains

# 本仓库只提供了 gradle-wrapper.properties（二进制 wrapper jar 不便提交）。
# 用本机 Gradle 生成一次 wrapper（Gradle 9.x 任意版本都行）：
gradle wrapper --gradle-version 9.8.0
# 若本机没装 Gradle：从 https://services.gradle.org/distributions/gradle-9.8.0-bin.zip 解压，
# 用 <解压目录>\bin\gradle.bat 代替下面的 .\gradlew

# 打包成可安装 zip（首次会下载 IntelliJ Platform SDK，约 1GB+，要耐心）
.\gradlew.bat buildPlugin
# 产物：build\distributions\lionbox-jetbrains-0.1.0.zip

# 直接在沙箱 IDE 里跑起来调试
.\gradlew.bat runIde

# 校验二进制兼容性（发布前必做）
.\gradlew.bat verifyPlugin
```

> 注意：Gradle 默认把缓存写到 `%USERPROFILE%\.gradle`。如果在受限环境里跑，
> 用 `.\gradlew.bat buildPlugin -g .\.gradle-home` 把缓存挪进工程目录。

## 3. 本地安装

1. `Settings/Preferences → Plugins → ⚙（齿轮）→ Install Plugin from Disk...`
2. 选 `build\distributions\lionbox-jetbrains-0.1.0.zip`
3. 重启 IDE → 右侧会出现 **LionBox** 工具窗按钮

## 4. 行为说明

- Tool Window 是**声明式注册**的（`plugin.xml` 里的 `com.intellij.toolWindow`），只有用户点开时才创建 UI、才初始化 JCEF。
- 打开后先显示"正在等待本地服务 http://127.0.0.1:8080 ..."，
  后台线程用 `java.net.http.HttpClient` 轮询 `GET /api/runtime/mode`：
  **收到任何 HTTP 响应（200/401/404 都算）= 端口已监听 = 就绪**，然后切到浏览器并 `browser.loadURL(BASE_URL)`。
- 120 秒仍连不上 → 显示原因 + 启动命令 + 「重新检测」按钮。
- `JBCefApp.isSupported()` 为 false（例如换了不带 JCEF 的 JDK）时，显示提示而不崩溃。
- 地址可用 JVM 参数覆盖（Help → Edit Custom VM Options）：
  `-Dlionbox.baseUrl=http://127.0.0.1:9090`，健康检查路径：`-Dlionbox.healthPath=/api/runtime/mode`。

## 5. 发布（确切命令）

```powershell
# 1) 首次发布：必须先手动上传 build/distributions/lionbox-jetbrains-0.1.0.zip
#    https://plugins.jetbrains.com/author/me → Add new plugin
#    官方原文："The first plugin publication must always be uploaded manually."
#
# 2) 后续版本：用 Gradle 自动发布
#    token 在 https://plugins.jetbrains.com/author/me/tokens 生成
.\gradlew.bat publishPlugin "-PintellijPlatformPublishingToken=YOUR_TOKEN"

# 3) 发布前签名（Marketplace 要求签名；signPlugin 会在 publishPlugin 前自动执行）
$env:CERTIFICATE_CHAIN = "C:\path\chain.crt"
$env:PRIVATE_KEY = "C:\path\private.pem"
$env:PRIVATE_KEY_PASSWORD = "******"
.\gradlew.bat signPlugin
```

频道（可选，`build.gradle.kts` 的 `intellijPlatform.publishing.channels`）：
`alpha` / `beta` / `eap`，例如 beta 用户需要自己加 `https://plugins.jetbrains.com/plugins/beta/list` 仓库。

官方链接：
[Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html) ·
[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) ·
[Marketplace 上传](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html) ·
[Verifying Plugin Compatibility](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)

## 6. 已知限制 / 风险

- **JCEF 依赖变更（2026.2）**：本插件已按新写法声明 `com.intellij.modules.jcef`；
  若你想同时支持 2025.3.1 之前的 IDE，只能拆分支发版（官方公告明确说了这一点）。
- `until-build` 留空 = 兼容所有未来版本，好处是不用每次大版本重发，风险是未来平台真的不兼容时用户也会装上；
  Marketplace 侧可以用"版本控制"限制。发布前务必跑 `verifyPlugin`。
- 若后端以后引入 Spring Security，默认 `X-Frame-Options: DENY` 对 JCEF 影响不大（JCEF 是顶层导航），
  但 JCEF 在自定义 JBR 下可能整体不可用，已做 `isSupported()` 兜底。
- Plugin Verifier 会拒绝"编造的 build number"，`since-build` 已按官方表格取 261，不要随意改大。
