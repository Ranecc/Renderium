# Renderium

Modern Minecraft rendering extension with Vulkan, DLSS, and advanced culling.

## Features

- **Extension Points**: Extends Minecraft 26.2+ official Vulkan renderer
- **DLSS Support**: NVIDIA DLSS 4, Frame Generation, and alternative super resolution (XeSS, FSR3)
- **Advanced Culling**: Frustum culling, occlusion culling, and distance culling
- **Custom Post-Processing**: Insert custom post-processing effects into the render pipeline
- **Dual Platform**: Supports both NeoForge and Fabric

### Dual Mode

- **Independently Mode** : Runs independently
- **Compatibility Mode** (With Sodium): Extends Sodium settings interface via Mixin injection, dynamic linking mode, compatible with other mod ecosystem

## Requirements

- Java 25+
- Minecraft 26.2+
- NeoForge 26.2+ or Fabric Loader 0.18.4+
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU (for super resolution)
- Vulkan-compatible GPU and drivers

## Project Structure

```
Renderium/
├── common/                  # Platform-independent core
│   └── src/main/java/com/renderium/
│       ├── api/             # Extension interfaces
│       ├── core/            # Core manager
│       ├── dlss/            # DLSS integration
│       └── culling/         # Culling algorithms
├── fabric/                  # Fabric-specific implementation
│   └── src/main/java/com/renderium/fabric/
│       ├── FabricEntry.java
│       └── mixin/
└── neoforge/                # NeoForge-specific implementation
    └── src/main/java/com/renderium/neoforge/
        ├── NeoForgeEntry.java
        └── mixin/
```

## Setup

### 1. Install Dependencies

Ensure you have:
- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+ (or use the included wrapper)

### 2. Build

```bash
# Generate Gradle wrapper
gradle wrapper --gradle-version=9.4.0

# Build all modules
./gradlew build

# Or build specific platform
./gradlew :fabric:build
./gradlew :neoforge:build
```

### 3. Run Development Client

```bash
# Fabric
./gradlew :fabric:runClient

# NeoForge
./gradlew :neoforge:runClient
```

## Extension Points

### RenderExtension

Implement `RenderExtension` to add custom rendering functionality:

```java
public class MyExtension implements RenderExtension {
    @Override
    public String getName() {
        return "MyExtension";
    }

    @Override
    public void onFrameBegin(int frameNumber, float deltaTime) {
        // Called at the start of each frame
    }

    @Override
    public void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {
        // Called after opaque rendering, before post-processing
    }
}
```

Register your extension:

```java
RenderiumCore.getInstance().registerExtension(new MyExtension());
```

### FrustumCuller

Implement `FrustumCuller` for custom culling algorithms:

```java
public class MyCuller implements FrustumCuller {
    @Override
    public void initialize(int maxDrawDistance) {
        // Initialize culling resources
    }

    @Override
    public boolean isVisible(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        // Custom visibility test
        return true;
    }
}
```

### PostProcessor

Implement `PostProcessor` to add custom post-processing effects:

```java
public class MyEffect implements PostProcessor {
    @Override
    public String getName() {
        return "MyEffect";
    }

    @Override
    public void process(long commandBuffer, TextureInputs inputs,
                       TextureOutput output, int width, int height) {
        // Apply custom post-processing
    }
}
```

## API Documentation

### Core Classes

- `RenderiumCore` - Central manager for all extensions
- `RenderExtension` - Extension point interface
- `FrustumCuller` - Custom culling interface
- `PostProcessor` - Post-processing effect interface

### DLSS Integration

- `DLSSManager` - Manages DLSS and super resolution technologies
- `DLSSMode` - DLSS operation mode
- `DLSSQuality` - Quality presets

### Culling

- `CullingController` - Coordinates multiple culling strategies
- `CullingStrategy` - Base interface for culling algorithms
- `FrustumCullingStrategy` - Default frustum culling
- `DistanceCullingStrategy` - Distance-based culling

## Integration with Minecraft 26.2

Minecraft 26.2+ includes official Vulkan support. Renderium extends this by:

1. **Mixin Injection**: Intercepts Minecraft's render pipeline at key points
2. **Extension Callbacks**: Notifies registered extensions at appropriate times
3. **Resource Access**: Provides access to Vulkan textures and command buffers
4. **Pipeline Extension**: Allows inserting custom render passes

## Roadmap

- [x] Project structure setup
- [x] Core API design
- [x] Extension interfaces
- [ ] Mixin implementation
- [ ] DLSS integration (NVIDIA Streamline SDK)
- [ ] Advanced culling algorithms
- [ ] Post-processing pipeline
- [ ] Performance optimization

## License & Compliance

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

### Third-Party Components

This mod includes the following third-party software components:

| Component | License | Purpose |
|-----------|---------|---------|
| NVIDIA Streamline SDK | MIT License | DLSS/Super Resolution core framework |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs License | DLSS super resolution and frame generation |
| LWJGL 3 | BSD License | Java native bindings |
| FastUtil | Apache 2.0 | High-performance collections |
| Sodium (Optional) | LGPL-3.0 License | Optional runtime dependency, dynamic linking via Mixin |

### Compliance Statement

This mod complies with all third-party license requirements:

- **Streamline SDK**: DLL files are distributed in their original, unmodified form with complete copyright and license notices. See [THIRD-PARTY-NOTICES.md](renderium/common/src/main/resources/THIRD-PARTY-NOTICES.md) for full details.
- **DLSS/DLSS-G**: Governed by the NVIDIA RTX SDKs License. This mod distributes these components as part of an application with substantial functionality.
- **Sodium** (Optional): This mod has an optional dependency on Sodium and is not a derivative work. In Full Performance Mode (without Sodium), there is zero contact with Sodium. In Compatibility Mode (with Sodium), Renderium extends Sodium's settings interface via Mixin injection, which constitutes "dynamic linking" explicitly permitted under LGPL-3.0 Section 4. All code is independently written without including any Sodium source code. See: https://github.com/CaffeineMC/sodium-fabric
- All DLL files are official NVIDIA originals, without any modification or reverse engineering.

For complete third-party license information:
- Source repository: [THIRD-PARTY-NOTICES.md](renderium/common/src/main/resources/THIRD-PARTY-NOTICES.md)
- Inside mod JAR: `META-INF/THIRD-PARTY-NOTICES.md`

## Disclaimer

This mod is not affiliated with or endorsed by Mojang Studios or Microsoft.  
This mod is not officially associated with NVIDIA Corporation. DLSS is a trademark of NVIDIA Corporation.
