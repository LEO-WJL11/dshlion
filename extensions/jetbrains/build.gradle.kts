// ---------------------------------------------------------------------------
// LionBox —— IntelliJ Platform 插件（Tool Window + JCEF 内嵌本地 Web UI）
//
// 官方文档：
//   IntelliJ Platform Gradle Plugin 2.x
//     https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
//   Tool Windows
//     https://plugins.jetbrains.com/docs/intellij/tool-windows.html
//   Embedded Browser (JCEF)
//     https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html
//
// 基线（2026-09-30 核实）：
//   插件 2.19.0 / Gradle 9.8.0 / 目标平台 2026.1（branch 261, Java 21）
// ---------------------------------------------------------------------------

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 目标平台由 gradle.properties 的 platformType / platformVersion 决定
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )

        // 面向 2026.2+ 构建时，JCEF 变成独立 bundled plugin，需要显式加这条依赖
        // （同时 plugin.xml 里要有 <depends>com.intellij.modules.jcef</depends>）。
        // 本工程用 2026.1 构建，JCEF 属于平台本体，因此默认注释掉以兼容 2026.1。
        // bundledPlugin("intellij.platform.ui.jcef")
    }
}

// 插件自身字节码目标 Java 21（2026.1 平台的 Java 版本）。
// 用 --release 21 而不是 source/target：既保证 API 面就是 Java 21，
// 也避免用本机更高的 JDK（如 26）编译时误用新 API 导致运行期 NoSuchMethodError。
// 注意：2026.2 平台用 Java 25 构建，官方明确建议"用 2026.1 构建"以保证兼容区间。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

intellijPlatform {
    pluginConfiguration {
        // 名称/描述/依赖等仍以 plugin.xml 为准，这里只覆盖构建号区间
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            // untilBuild 故意不设置：不设 = 兼容所有未来版本（官方推荐的 "Open-End" 做法）
            // https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html
        }
    }

    publishing {
        // 发布令牌：https://plugins.jetbrains.com/author/me/tokens
        // 首次发布必须手动上传 zip，之后才能用 publishPlugin
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }

    // 签名（正式发布必需）通过环境变量提供：
    //   CERTIFICATE_CHAIN / PRIVATE_KEY / PRIVATE_KEY_PASSWORD
    // 详见 https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
}

// 生成"可搜索选项"需要拉起一个 IDE 实例，这里不需要，关掉以加快构建
// （用 matching 而不是类型化访问器，避免不同插件版本的 DSL 差异导致配置失败）
tasks.matching { it.name == "buildSearchableOptions" }.configureEach {
    enabled = false
}
