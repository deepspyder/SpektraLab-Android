// 顶层构建脚本：只声明插件版本，不应用。
// 版本组合：Gradle 8.13 + AGP 8.11.2 + Kotlin 2.1.0（互相兼容，compileSdk 36）。
// 已按本机已装的 SDK（android-36、NDK 27.0.12077973、CMake 3.22.1）对齐。
plugins {
    id("com.android.application") version "8.11.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
