// Renderium - Modern Minecraft Rendering Extension
// Fabric-only build (NeoForge disabled - MC 26.2-snapshot-3 not yet supported)
// Migrated to renderium subdirectory for organization

pluginManagement {
    repositories {
        // CN Mirrors for faster plugin download (priority)
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")

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

// Configure dependency repositories with CN mirrors for optimal performance
dependencyResolutionManagement {
    repositories {
        // === CN Mirrors (Priority) ===

        // Aliyun - Fastest and most complete
        maven("https://maven.aliyun.com/repository/public") {
            name = "Aliyun Public"
        }

        // Tencent Cloud (backup)
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            name = "Tencent Cloud"
        }

        // Huawei Cloud (backup)
        maven("https://repo.huaweicloud.com/repository/maven/") {
            name = "Huawei Cloud"
        }

        // === Specialized Repositories ===

        // Mojang official library
        maven("https://libraries.minecraft.net/") {
            name = "Mojang"
            content {
                includeGroup("com.mojang")
            }
        }

        // Fabric repository (primary for this project)
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }

        // Fallback to Maven Central
        mavenCentral()
    }
}
