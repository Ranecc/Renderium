# Renderium

Modern Minecraft rendering extension with Vulkan, DLSS, and advanced optimization technologies.

## Features

- **Extension Points**: Extends Minecraft 26.2+ official Vulkan renderer
- **Super Resolution**: NVIDIA DLSS, Intel XeSS, AMD FSR support via NVIDIA Streamline SDK
- **Frame Generation**: DLSS Frame Generation and FSR Frame Generation support
- **NVIDIA Reflex**: Low latency mode support for competitive gaming
- **Advanced Culling**: Frustum culling, occlusion culling (BFS-based), and GPU-driven LOD
- **Custom Post-Processing**: Insert custom post-processing effects into the render pipeline
- **Dual Platform**: Supports both NeoForge and Fabric
- **Dual Mode**: Independent mode and Sodium compatibility mode
- **C++ Acceleration**: Optional native library for performance-critical algorithms

## Requirements

- Java 25+
- Minecraft 26.2-snapshot-3+
- NeoForge 21.11.0-beta+ or Fabric Loader 0.18.5+
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU (for super resolution features)
- Vulkan-compatible GPU and drivers

## Project Structure

```
Renderium/
├── common/                          # Platform-independent core
│   └── src/main/java/com/renderium/
│       ├── api/                     # Extension interfaces
│       │   ├── RenderExtension.java # Main extension point
│       │   ├── FrustumCuller.java   # Custom culling interface
│       │   └── PostProcessor.java   # Post-processing interface
│       ├── core/                    # Core manager
│       │   ├── RenderiumCore.java   # Central manager
│       │   ├── RenderiumDualModeManager.java
│       │   └── RenderiumMode.java
│       ├── config/                  # Configuration system
│       │   ├── RenderiumConfig.java
│       │   └── structure/           # Option types
│       ├── dlss/                    # DLSS integration
│       │   └── DLSSManager.java
│       ├── superres/                # Super resolution adapters
│       │   ├── SuperResolutionManager.java
│       │   ├── DLSSAdapter.java
│       │   ├── FSRAdapter.java
│       │   └── XeSSAdapter.java
│       ├── framegen/                # Frame generation
│       │   ├── FrameGeneratorManager.java
│       │   ├── DLSSFGAdapter.java
│       │   └── FSRFGAdapter.java
│       ├── culling/                 # Culling system
│       │   └── CullingController.java
│       ├── streamline/              # NVIDIA Streamline SDK integration
│       │   ├── SLContext.java
│       │   └── VulkanStreamlineBridge.java
│       ├── reflex/                  # NVIDIA Reflex
│       │   └── ReflexManager.java
│       ├── accel/                   # C++ accelerator
│       │   └── RenderiumAccelerator.java
│       ├── pipeline/                # Async render pipeline
│       │   └── AsyncRenderPipeline.java
│       ├── optimization/            # Performance optimizations
│       ├── interception/            # Render interception layer
│       ├── graphics/                # Graphics backend
│       ├── shader/                  # Shader system
│       ├── bridge/                  # Minecraft bridge
│       ├── mixin/                   # Mixin hooks
│       └── ui/                      # Settings UI
├── fabric/                          # Fabric-specific implementation
│   └── src/main/java/com/renderium/fabric/
│       ├── RenderiumMod.java
│       ├── mixin/
│       └── platform/
└── neoforge/                        # NeoForge-specific implementation
    └── src/main/java/com/renderium/neoforge/
        ├── RenderiumMod.java
        ├── mixin/
        └── platform/
```

## Setup

### 1. Install Dependencies

Ensure you have:
- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+ (or use the included wrapper)

### 2. Build

```bash
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
    public int getPriority() {
        return 500; // Lower = earlier execution
    }

    @Override
    public void onVulkanPipelineInit(long vulkanDevice) {
        // Called after Vulkan pipeline initialization
    }

    @Override
    public void onFrameBegin(int frameNumber, float deltaTime) {
        // Called at the start of each frame
    }

    @Override
    public void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {
        // Called after opaque rendering, before post-processing
    }

    @Override
    public void onPostProcessingBegin(long commandBuffer, long sceneTexture) {
        // Called at the start of post-processing
    }

    @Override
    public void onBeforeOutput(long commandBuffer, long outputTexture, int displayWidth, int displayHeight) {
        // Called before final output to screen
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
    public void updateCamera(float cameraX, float cameraY, float cameraZ,
                            float pitch, float yaw, float fov) {
        // Update camera frustum
    }

    @Override
    public boolean isVisible(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        // Custom visibility test
        return true;
    }

    @Override
    public List<Integer> computeVisibleChunks(List<ChunkBounds> chunks,
                                               float cameraX, float cameraY, float cameraZ) {
        // Return visible chunk indices
        return List.of();
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
    public int getOrder() {
        return 500; // Execution order
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

- `RenderiumCore` - Central manager for all extensions and rendering technologies
- `RenderExtension` - Extension point interface
- `FrustumCuller` - Custom culling interface
- `PostProcessor` - Post-processing effect interface

### Super Resolution

- `SuperResolutionManager` - Manages DLSS/FSR/XeSS technologies
- `DLSSAdapter` - NVIDIA DLSS integration
- `FSRAdapter` - AMD FSR integration
- `XeSSAdapter` - Intel XeSS integration

### Frame Generation

- `FrameGeneratorManager` - Manages frame generation technologies
- `DLSSFGAdapter` - DLSS Frame Generation
- `FSRFGAdapter` - FSR Frame Generation

### Reflex Low Latency

- `ReflexManager` - NVIDIA Reflex low latency mode

### Culling

- `CullingController` - Coordinates multiple culling strategies
- `BfsOcclusion` - BFS-based occlusion culling

### Streamline SDK

- `SLContext` - Streamline SDK context management
- `VulkanStreamlineBridge` - Vulkan-Streamline integration

### Configuration

- `RenderiumConfig` - Main configuration class
- Config file location: `<game_dir>/config/renderium.properties`

## Dual Mode System

### Independent Mode
Runs independently without Sodium, full feature set available.

### Compatibility Mode (with Sodium)
Extends Sodium settings interface via Mixin injection, dynamic linking mode, compatible with other mod ecosystem.

Mode detection:
```java
RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
if (dualMode.isPerformanceModPresent()) {
    // Sodium is present, running in compatibility mode
}
```

## Integration with Minecraft 26.2

Minecraft 26.2+ includes official Vulkan support. Renderium extends this by:

1. **Mixin Injection**: Intercepts Minecraft's render pipeline at key points
2. **Extension Callbacks**: Notifies registered extensions at appropriate times
3. **Resource Access**: Provides access to Vulkan textures and command buffers
4. **Pipeline Extension**: Allows inserting custom render passes

## Debug Mode

Enable debug logging with JVM arguments:

```bash
# Basic debug output
-Drenderium.debug=true

# Verbose tracing
-Drenderium.debug.verbose=true
```

## License & Compliance

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

### Third-Party Components

| Component | License | Purpose |
|-----------|---------|---------|
| NVIDIA Streamline SDK | MIT License | DLSS/Super Resolution core framework |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs License | DLSS super resolution and frame generation |
| LWJGL 3 | BSD License | Java native bindings |
| FastUtil | Apache 2.0 | High-performance collections |
| Sodium (Optional) | LGPL-3.0 License | Optional runtime dependency, dynamic linking via Mixin |

### Compliance Statement

- **Streamline SDK**: Distributed in original, unmodified form with complete copyright notices
- **DLSS/DLSS-G**: Governed by NVIDIA RTX SDKs License, distributed as part of an application with substantial functionality
- **Sodium**: Optional dependency, not a derivative work. Dynamic linking permitted under LGPL-3.0 Section 4

## Disclaimer

This mod is not affiliated with or endorsed by Mojang Studios or Microsoft.  
This mod is not officially associated with NVIDIA Corporation. DLSS is a trademark of NVIDIA Corporation.
