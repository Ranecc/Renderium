# Renderium

Modern Minecraft rendering extension with Vulkan, DLSS, and advanced optimization technologies.

> **Development Status: Early Development**
>
> This project is in active development. Large portions of the codebase are skeleton implementations or placeholder code. Many advertised features are not yet fully implemented or only work in a simulated state.

---

## Features

- **Extension Points**: Designed to extend the Minecraft 26.2+ official Vulkan renderer
- **Super Resolution**: DLSS / XeSS / FSR adapter framework (via NVIDIA Streamline SDK)
- **Frame Generation**: DLSS Frame Generation and FSR Frame Generation adapter framework
- **Advanced Culling**: Frustum culling, BFS occlusion culling framework, HiZ management
- **Custom Post-Processing**: Shader system and post-processing pipeline framework
- **Dual Platform**: Fabric (enabled) / NeoForge (planned)
- **Dual Mode**: Independent mode and Sodium compatibility mode (framework layer)
- **C++ Acceleration**: Optional native library interface (reserved)

## Requirements

- Java 25+
- Minecraft 26.2-snapshot-7+
- Fabric Loader 0.18.5+
- Vulkan-compatible GPU and drivers
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU (for super resolution features, requires Streamline SDK)

> **NeoForge Note**: The NeoForge module is currently disabled because NeoForge 21.x does not yet support Minecraft 26.2-snapshot. It will be re-enabled once upstream support is available.

## Project Structure

```
Renderium/
├── common/                          # Platform-independent core
│   └── src/main/java/com/ranecc/renderium/
│       ├── application/             # Application layer (core lifecycle)
│       ├── domain/                  # Domain layer (enums, config models)
│       ├── feature/                 # Feature modules
│       │   ├── blaze3d/            # Blaze3D optimizer framework
│       │   ├── culling/            # Culling system framework
│       │   ├── intercept/          # Render interception layer
│       │   ├── pipeline/           # Render pipeline framework
│       │   └── shader/             # Shader system framework
│       ├── infrastructure/          # Infrastructure layer
│       │   ├── config/             # Configuration management
│       │   ├── gpu/                # GPU resource management
│       │   ├── nativeLib/          # Native library FFI bindings
│       │   └── vulkan/             # Vulkan utilities
│       ├── presentation/            # Presentation layer (UI system)
│       ├── tech/                    # Technology integration layer
│       │   ├── dlss/               # DLSS integration framework
│       │   ├── framegen/           # Frame generation framework
│       │   ├── reflex/             # Reflex low-latency framework
│       │   ├── streamline/         # Streamline SDK bindings
│       │   └── superres/           # Super resolution adapters
│       └── platform/               # Platform abstraction layer
├── fabric/                          # Fabric-specific implementation
└── neoforge/                        # NeoForge-specific implementation (disabled)
```

## Setup

### 1. Install Dependencies

- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+ (or use the included wrapper)

### 2. Build

```bash
# Build all modules
./gradlew build

# Fabric only
./gradlew :fabric:build
```

### 3. Run Development Client

```bash
./gradlew :fabric:runClient
```

## Core API

### RenderiumCore

Central manager providing lifecycle control:

```java
RenderiumCore core = RenderiumCore.getInstance();
core.initialize(deviceHandle);      // Initialize
core.processFrame(deltaTime);       // Frame processing
core.shutdown();                    // Shutdown
```

### Configuration

- Main config class: `RenderiumConfig`
- Config file location: `<game_dir>/config/renderium.properties`

## Dual Mode System

### Independent Mode
Runs independently without Sodium.

### Compatibility Mode (with Sodium)
Extends Sodium settings interface via Mixin injection (framework reserved).

```java
RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
if (dualMode.isPerformanceModPresent()) {
    // Sodium detection (currently a placeholder)
}
```

## Debug Mode

Enable debug logging with JVM arguments:

```bash
# Basic debug output
-Drenderium.debug=true

# Verbose tracing
-Drenderium.debug.verbose=true
```

## AI Assistance & Development Status

**This project is developed with significant AI assistance.** The codebase contains:

- **Skeleton code**: Many methods are placeholder implementations returning `true`/`false`/`null`
- **Hardcoded values**: FFM struct offsets, resolution scale ratios, etc.
- **Unimplemented features**: Mod detection, native acceleration library, parts of the Streamline SDK call chain
- **API drift**: Some API examples from earlier README versions no longer match the actual code

See [Skeleton and Gaps](.context/Renderium/skeleton-and-gaps.md) for the full list.

Issues and Pull Requests for corrections are warmly welcome. Compatibility-related issues (mod compatibility, Minecraft version compatibility, GPU/driver compatibility, etc.) are especially appreciated.

## License & Compliance

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

### Third-Party Components

| Component | License | Purpose |
|-----------|---------|---------|
| NVIDIA Streamline SDK | MIT License | DLSS/Super Resolution core framework |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs License | DLSS super resolution and frame generation |
| LWJGL 3 | BSD License | Java native bindings |
| FastUtil | Apache 2.0 | High-performance collections |
| Sodium (Optional) | LGPL-3.0 License | Optional runtime dependency, dynamic linking |

## Disclaimer

This mod is not affiliated with or endorsed by Mojang Studios or Microsoft.  
This mod is not officially associated with NVIDIA Corporation. DLSS is a trademark of NVIDIA Corporation.
