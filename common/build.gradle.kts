// Renderium Common Module
// Platform-independent rendering extension API
// 参考 Sodium (caffeinemc) 多加载器架构：common 模块也使用 Fabric Loom

plugins {
    id("java-library")
    // 关键：common 模块也需要 Fabric Loom 来正确解析 Minecraft 依赖树（包括 brigadier）
    id("net.fabricmc.fabric-loom") version "1.15.4"
}

val minecraftVersion = rootProject.property("minecraft.version") as String
val lwjglVersion = rootProject.property("lwjgl.version") as String

base {
    archivesName = "renderium-common"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25

    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // 核心：通过 Loom 获取 Minecraft 完整依赖树（包含 brigadier 等传递依赖）
    minecraft(group = "com.mojang", name = "minecraft", version = minecraftVersion)

    // Fabric Loader（编译时 API）
    compileOnly("net.fabricmc:fabric-loader:0.18.5")

    // LWJGL 3.4.1 - Vulkan 绑定 + FFM API 集成（仅编译时 API）
    api("org.lwjgl:lwjgl:$lwjglVersion")
    api("org.lwjgl:lwjgl-vulkan:$lwjglVersion")
    api("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    api("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    api("org.lwjgl:lwjgl-stb:$lwjglVersion")
    api("org.lwjgl:lwjgl-vma:$lwjglVersion")

    // JOML - 数学库
    api("org.joml:joml:1.10.5")

    // FastUtil - 高性能集合
    api("it.unimi.dsi:fastutil:8.5.12")

    // SLF4J - 日志接口
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // JSR-305 / jspecify - 空值注解
    compileOnly("org.jspecify:jspecify:1.0.0")

    // 注解处理
    compileOnly("org.jetbrains:annotations:24.0.0")

    // JUnit 5 - 测试框架
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1")
}

// 配置测试任务使用 JUnit 5
tasks.test {
    useJUnitPlatform()
}

loom {
    accessWidenerPath = file("src/main/resources/renderium-common.accesswidener")

    mixin {
        useLegacyMixinAp = false
    }
}

// ==================== SourceSet 导出机制（参考 Sodium）====================
// 将编译输出导出为可消费的 Configuration，供 fabric/neoforge 模块使用

fun exportSourceSetJava(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Java") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }
    val compileTask = tasks.getByName<JavaCompile>(sourceSet.compileJavaTaskName)
    artifacts.add(configuration.name, compileTask.destinationDirectory) {
        builtBy(compileTask)
    }
}

fun exportSourceSetResources(name: String, sourceSet: SourceSet) {
    val configuration = configurations.create("${name}Resources") {
        isCanBeResolved = true
        isCanBeConsumed = true
    }
    val processTask = tasks.getByName<ProcessResources>(sourceSet.processResourcesTaskName)
    artifacts.add(configuration.name, processTask.destinationDir) {
        builtBy(processTask)
    }
}

// 导出 main sourceSet
exportSourceSetJava("commonMain", sourceSets["main"])
exportSourceSetResources("commonMain", sourceSets["main"])

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // Java 25: 抑制 forRemoval API 警告（Unsafe.putFloat/getFloat 等已标记为 forRemoval）
    // 这些 API 在 JVM 层面仍然可用，仅是编译器提示
    options.compilerArgs.add("-Xlint:-options")
    options.compilerArgs.add("-Xlint:-removal")
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Javadoc> {
    options {
        this as StandardJavadocDocletOptions
        addStringOption("-source", "25")
        addBooleanOption("-enable-preview", true)
        addBooleanOption("Xdoclint:none", true)
        addStringOption("Xmaxwarns", "1")
    }
}

tasks.named<Javadoc>("javadoc") {
    isFailOnError = false
}
