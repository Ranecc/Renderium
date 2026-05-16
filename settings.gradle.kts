// Renderium - Modern Minecraft Rendering Extension
// Fabric-only build (NeoForge disabled - MC 26.2-snapshot-3 not yet supported)
// Migrated to renderium subdirectory for organization

pluginManagement {
    repositories {
        // CN Mirrors for faster plugin download (priority)
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://bmclapi2.bangbang93.com/maven") {
            name = "BMCLAPI"
        }

        // Official sources (fallback)
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        mavenCentral()
    }
}

rootProject.name = "renderium"

// Include modules - common + fabric only (NeoForge requires MC 1.21.x)
include("common")
include("fabric")

// NeoForge module temporarily disabled:
// Reason: Minecraft 26.2-snapshot-3 is not supported by NeoForge 21.x
// Latest NeoForge (21.11.0-beta) only supports MC 1.21.x
// Re-enable when: NeoForge releases version for MC 26.x or downgrade MC to 1.21.x
//
// To re-enable: uncomment the line below and ensure MC version compatibility
// include("neoforge")

// 依赖仓库由根 build.gradle.kts 的 allprojects.repositories 统一管理
// （含阿里云、腾讯云、华为云、BMCLAPI Fabric 镜像、Maven Central）
