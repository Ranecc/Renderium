// Renderium NeoForge Module
// NeoForge-specific implementation using ModDevGradle
// Minecraft 26.1.2 with NeoForge 21.x

plugins {
    id("net.neoforged.moddev") version "2.0.84"  // ✅ ModDevGradle 2.0.84 (兼容 Gradle 8.x，2025-05-02 发布)
}

val minecraftVersion = rootProject.property("minecraft.version") as String
val neoforgeVersion = rootProject.property("neoforge.version") as String
val lwjglVersion = rootProject.property("lwjgl.version") as String

repositories {
    // NeoForge 官方仓库
    maven("https://maven.neoforged.net/releases") {
        name = "NeoForge Official"
        content {
            includeGroup("net.neoforged")
            includeGroup("net.minecraftforge")
        }
    }
    mavenCentral()
}

neoForge {
    version = neoforgeVersion

    // NeoForge 21.x built-in Mixin support
    // Mixin configuration via META-INF/neoforge.mods.toml

    mods {
        create("renderium") {
            sourceSet(sourceSets["main"])
        }
    }

    // Runs configuration - using defaults
    // For custom run configs, refer to ModDevGradle documentation
}

dependencies {
    // Common module
    implementation(project(":common"))

    // LWJGL 3 for native Vulkan access
    implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")

    compileOnly("org.jetbrains:annotations:24.0.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25

    // Override NeoForge default toolchain (Java 21) to use Java 25
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("renderium-neoforge")
    manifest {
        attributes(
            "Mixins" to "renderium.neoforge.mixins.json",
            "EntryPoint" to "com.renderium.neoforge.RenderiumMod"
        )
    }
}
