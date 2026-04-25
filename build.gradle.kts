// Renderium Root Build Configuration
// No Architectury dependency - clean dual-loader support
// Organized in renderium subdirectory

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

allprojects {
    group = "com.renderium"
    version = "1.0.0-SNAPSHOT"

    // Only common repositories - each module adds its own
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
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
