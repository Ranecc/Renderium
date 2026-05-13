plugins {
    id("java-library")
    id("net.fabricmc.fabric-loom") version "1.15.4"
}

val minecraftVersion = rootProject.property("minecraft.version") as String
val lwjglVersion = rootProject.property("lwjgl.version") as String

base { archivesName = "renderium-common" }

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    minecraft(group = "com.mojang", name = "minecraft", version = minecraftVersion)
    compileOnly("net.fabricmc:fabric-loader:0.18.5")
    api("org.lwjgl:lwjgl:$lwjglVersion")
    api("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    api("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    api("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    api("org.lwjgl:lwjgl-stb:$lwjglVersion")
    api("org.lwjgl:lwjgl-vma:$lwjglVersion")
    api("org.joml:joml:1.10.5")
    api("it.unimi.dsi:fastutil:8.5.12")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains:annotations:24.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.test { useJUnitPlatform() }

loom {
    accessWidenerPath = file("src/main/resources/renderium-common.accesswidener")
    mixin { useLegacyMixinAp = false }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("--enable-preview")
    options.compilerArgs.add("-Xlint:-options")
    options.compilerArgs.add("-Xlint:-removal")
}
