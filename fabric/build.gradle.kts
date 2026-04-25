// Renderium Fabric Module
// Fabric-specific implementation with Loom
// 参考 Sodium (caffeinemc) 多加载器架构

plugins {
    id("net.fabricmc.fabric-loom") version "1.15.4"
}

base {
    archivesName = "renderium-fabric"
}

val minecraftVersion = rootProject.property("minecraft.version") as String
val fabricLoaderVersion = rootProject.property("fabric.loader.version") as String
val fabricApiVersion = rootProject.property("fabric.api.version") as String

// ==================== SourceSet 导入机制（参考 Sodium）====================
// 从 common 模块导入编译输出

val configurationCommonJava: Configuration = configurations.create("commonModJava") {
    isCanBeResolved = true
}

val configurationCommonResources: Configuration = configurations.create("commonModResources") {
    isCanBeResolved = true
}

dependencies {
    // 使用 common 模块导出的 Configuration（不是默认的 'main'）
    configurationCommonJava(project(path = ":common", configuration = "commonMainJava"))
    configurationCommonResources(project(path = ":common", configuration = "commonMainResources"))
}

sourceSets.apply {
    main {
        compileClasspath += configurationCommonJava
        runtimeClasspath += configurationCommonJava

        // 仅排除确实无法编译的文件（Vulkan Backend 需要 Intermediary 映射，Sodium 需要 Sodium 依赖）
        java {
            // Vulkan Backend Mixin（需要 blaze3d.vulkan 包 - Intermediary 映射问题）
            exclude("com/renderium/fabric/mixin/MixinVulkanBackend.java")
            // Sodium 视频设置 Mixin（需要 sodium 依赖，未安装时跳过）
            exclude("com/renderium/fabric/mixin/SodiumVideoSettingsMixin.java")
            // 注意：RenderiumModMenuIntegration 已移除排除 - ModMenu 集成需要此类
        }
    }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.terraformersmc.com/")  // ModMenu 官方仓库

    flatDir {
        name = "localLibs"
        dirs("libs")
    }
}

dependencies {
    // Minecraft（通过 Loom 获取完整依赖树）
    minecraft("com.mojang:minecraft:$minecraftVersion")

    // Fabric Loader
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Fabric API（嵌入到最终 JAR）
    api("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 注解支持
    compileOnly("org.jetbrains:annotations:24.0.0")

    // 本地模组依赖（开发时使用）
    if (file("${rootProject.projectDir}/mods").exists()) {
        implementation(fileTree("${rootProject.projectDir}/mods") { include("*.jar") })
    }
}

loom {
    accessWidenerPath.set(file("src/main/resources/renderium-fabric.accesswidener"))

    mixin {
        useLegacyMixinAp = false
    }

    // Configure download mirrors for Minecraft assets (CN optimization)
    // Uses BMCLAPI (Bukkit MCLib) for faster downloads in China
    runs {
        named("client") {
            client()
            configName = "Fabric/Client"
            ideConfigGenerated(true)
            runDir("run")
            vmArgs.addAll(listOf(
                "-Dmixin.debug.export=true",
                "-Dmixin.debug.verbose=true"
            ))
        }
    }
}

tasks {
    jar {
        from(configurationCommonJava)
        destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods"))
    }

    processResources {
        from(configurationCommonResources)
    }

    named<Jar>("jar") {
        manifest {
            attributes(
                "Mixins" to "renderium.fabric.mixins.json",
                "EntryPoint" to "com.renderium.fabric.RenderiumMod"
            )
        }
    }
}
