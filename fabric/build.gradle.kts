plugins { id("net.fabricmc.fabric-loom") version "1.15.4" }
base { archivesName = "renderium-fabric" }

val minecraftVersion = rootProject.property("minecraft.version") as String
val fabricLoaderVersion = rootProject.property("fabric.loader.version") as String
val fabricApiVersion = rootProject.property("fabric.api.version") as String

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    api("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    implementation(project(":common"))
    compileOnly("org.jetbrains:annotations:24.0.0")
}

loom {
    accessWidenerPath.set(file("src/main/resources/renderium-fabric.accesswidener"))
    mixin { useLegacyMixinAp = false }
    runs {
        named("client") { client(); configName = "Fabric/Client"; ideConfigGenerated(true); runDir("run") }
    }
}

tasks {
    jar { destinationDirectory.set(file(rootProject.layout.buildDirectory).resolve("mods")) }
    named<Jar>("jar") {
        manifest {
            attributes(
                "Mixins" to "renderium.fabric.mixins.json",
                "EntryPoint" to "com.ranecc.renderium.fabric.RenderiumFabricMod"
            )
        }
    }
}
