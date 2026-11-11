import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.spektralab.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spektralab.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.4"
        testInstrumentationRunner = "com.spektralab.android.FunctionalAuditInstrumentation"

        // Vulkan 与 LibRaw 在 64 位 ARM 上最稳；加 x86_64 方便在模拟器上跑。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // 引擎是 C++20。真实引擎移植（M0）时可能需要补充它自己的编译标志。
                cppFlags += "-std=c++20"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile", "app/release-signing/spektralab-release.jks"))
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias", "spektralab-release")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// 引擎资源 / 着色器同步（也可直接 `python tools/sync_resources.py`）。
// 手动运行：./gradlew syncEngineResources 或 ./gradlew compileEngineShaders
// ---------------------------------------------------------------------------
val python = "python" // Windows 下用 python；如不在 PATH，改成绝对路径

tasks.register<Exec>("syncEngineResources") {
    group = "spektralab"
    description = "把引擎资源同步到 assets/engine/resources/"
    workingDir = rootProject.projectDir
    commandLine(python, "tools/sync_resources.py")
}

tasks.register<Exec>("compileEngineShaders") {
    group = "spektralab"
    description = "用 glslang 编译 Vulkan SPIR-V（需要安装 glslangValidator）"
    workingDir = rootProject.projectDir
    commandLine(python, "tools/compile_shaders.py")
}
