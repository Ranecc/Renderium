// Renderium Root Build Configuration
// No Architectury dependency - clean dual-loader support
// Organized in renderium subdirectory

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

allprojects {
    group = "com.ranecc.renderium"
    version = "1.1.0-SNAPSHOT"

    // 配置仓库 - 使用镜像加速
    repositories {
        // 阿里云镜像（优先）
        maven("https://maven.aliyun.com/repository/public") {
            name = "Aliyun"
        }
        // 腾讯云镜像
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") {
            name = "Tencent"
        }
        // 华为云镜像
        maven("https://repo.huaweicloud.com/repository/maven/") {
            name = "Huawei"
        }
        // BMCLAPI 镜像（加速 Fabric + Mojang 依赖下载，国内首选）
        maven("https://bmclapi2.bangbang93.com/maven") {
            name = "BMCLAPI"
        }
        // Fabric 官方仓库（直连，作为 BMCLAPI 的后备）
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
            content {
                includeGroup("net.fabricmc")
            }
        }
        // Mojang 官方库（BMCLAPI 后备，仅用于 Minecraft 原生依赖）
        maven("https://libraries.minecraft.net/") {
            name = "Mojang"
            content {
                includeGroup("com.mojang")
            }
        }
        // Maven Central 官方
        mavenCentral()
        // Gradle Plugin Portal
        gradlePluginPortal()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf(
            "-Xlint:deprecation",
            "-Xlint:unchecked"
        ))
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                "Manifest-Version" to "1.0",
                "Specification-Title" to project.name,
                "Specification-Version" to project.version,
                "Specification-Vendor" to "Renderium"
            )
        }
    }
}
