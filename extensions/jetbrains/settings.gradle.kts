pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// 工具链自动下载（foojay resolver）。
// 【为什么需要】IntelliJ Platform Gradle Plugin 2.x 会给工程挂一个 Java 21 的工具链要求，
// 本机只装了 Java 26，构建直接失败：
//   "Cannot find a Java installation ... matching: {languageVersion=21}"
// 加上这个插件后，Gradle 会自己下载一个匹配的 JDK（Temurin 21）到 gradle user home，
// 既不用手工装 JDK，也不会去动用户的 JAVA_HOME。
// https://docs.gradle.org/current/userguide/toolchains.html#sub:download_repositories
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lionbox-jetbrains"
