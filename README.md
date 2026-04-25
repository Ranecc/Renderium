# Renderium

现代 Minecraft 渲染扩展，支持 Vulkan、DLSS 和高级优化技术。

## 特性

- **扩展点**：扩展 Minecraft 26.2+ 官方 Vulkan 渲染器
- **超分辨率**：通过 NVIDIA Streamline SDK 支持 NVIDIA DLSS、Intel XeSS、AMD FSR
- **帧生成**：支持 DLSS 帧生成和 FSR 帧生成技术
- **NVIDIA Reflex**：支持低延迟模式，提升竞技游戏体验
- **高级剔除**：视锥剔除、遮挡剔除（基于 BFS）和 GPU 驱动 LOD
- **自定义后处理**：将自定义后处理效果插入渲染管线
- **双平台**：同时支持 NeoForge 和 Fabric
- **双模式**：独立模式和 Sodium 兼容模式
- **C++ 加速**：可选原生库加速性能关键算法

## 环境要求

- Java 25+
- Minecraft 26.2-snapshot-3+
- NeoForge 21.11.0-beta+ 或 Fabric Loader 0.18.5+
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU（用于超分辨率功能）
- Vulkan 兼容 GPU 和驱动程序

## 项目结构

```
Renderium/
├── common/                          # 平台无关核心
│   └── src/main/java/com/renderium/
│       ├── api/                     # 扩展接口
│       │   ├── RenderExtension.java # 主扩展点接口
│       │   ├── FrustumCuller.java   # 自定义剔除接口
│       │   └── PostProcessor.java   # 后处理接口
│       ├── core/                    # 核心管理器
│       │   ├── RenderiumCore.java   # 中心管理器
│       │   ├── RenderiumDualModeManager.java
│       │   └── RenderiumMode.java
│       ├── config/                  # 配置系统
│       │   ├── RenderiumConfig.java
│       │   └── structure/           # 选项类型
│       ├── dlss/                    # DLSS 集成
│       │   └── DLSSManager.java
│       ├── superres/                # 超分辨率适配器
│       │   ├── SuperResolutionManager.java
│       │   ├── DLSSAdapter.java
│       │   ├── FSRAdapter.java
│       │   └── XeSSAdapter.java
│       ├── framegen/                # 帧生成
│       │   ├── FrameGeneratorManager.java
│       │   ├── DLSSFGAdapter.java
│       │   └── FSRFGAdapter.java
│       ├── culling/                 # 剔除系统
│       │   └── CullingController.java
│       ├── streamline/              # NVIDIA Streamline SDK 集成
│       │   ├── SLContext.java
│       │   └── VulkanStreamlineBridge.java
│       ├── reflex/                  # NVIDIA Reflex
│       │   └── ReflexManager.java
│       ├── accel/                   # C++ 加速器
│       │   └── RenderiumAccelerator.java
│       ├── pipeline/                # 异步渲染管线
│       │   └── AsyncRenderPipeline.java
│       ├── optimization/            # 性能优化
│       ├── interception/            # 渲染拦截层
│       ├── graphics/                # 图形后端
│       ├── shader/                  # 着色器系统
│       ├── bridge/                  # Minecraft 桥接
│       ├── mixin/                   # Mixin 钩子
│       └── ui/                      # 设置界面
├── fabric/                          # Fabric 专属实现
│   └── src/main/java/com/renderium/fabric/
│       ├── RenderiumMod.java
│       ├── mixin/
│       └── platform/
└── neoforge/                        # NeoForge 专属实现
    └── src/main/java/com/renderium/neoforge/
        ├── RenderiumMod.java
        ├── mixin/
        └── platform/
```

## 环境搭建

### 1. 安装依赖

确保你已安装：
- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+（或使用包含的 wrapper）

### 2. 编译

```bash
# 编译所有模块
./gradlew build

# 或编译特定平台
./gradlew :fabric:build
./gradlew :neoforge:build
```

### 3. 运行开发客户端

```bash
# Fabric
./gradlew :fabric:runClient

# NeoForge
./gradlew :neoforge:runClient
```

## 扩展点

### RenderExtension

实现 `RenderExtension` 接口以添加自定义渲染功能：

```java
public class MyExtension implements RenderExtension {
    @Override
    public String getName() {
        return "MyExtension";
    }

    @Override
    public int getPriority() {
        return 500; // 数值越小越早执行
    }

    @Override
    public void onVulkanPipelineInit(long vulkanDevice) {
        // 在 Vulkan 管线初始化后调用
    }

    @Override
    public void onFrameBegin(int frameNumber, float deltaTime) {
        // 在每帧开始时调用
    }

    @Override
    public void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {
        // 在不透明渲染完成后、后处理前调用
    }

    @Override
    public void onPostProcessingBegin(long commandBuffer, long sceneTexture) {
        // 在后处理链开始时调用
    }

    @Override
    public void onBeforeOutput(long commandBuffer, long outputTexture, int displayWidth, int displayHeight) {
        // 在最终输出到屏幕前调用
    }
}
```

注册你的扩展：

```java
RenderiumCore.getInstance().registerExtension(new MyExtension());
```

### FrustumCuller

实现 `FrustumCuller` 接口以使用自定义剔除算法：

```java
public class MyCuller implements FrustumCuller {
    @Override
    public void initialize(int maxDrawDistance) {
        // 初始化剔除资源
    }

    @Override
    public void updateCamera(float cameraX, float cameraY, float cameraZ,
                            float pitch, float yaw, float fov) {
        // 更新相机视锥体
    }

    @Override
    public boolean isVisible(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        // 自定义可见性测试
        return true;
    }

    @Override
    public List<Integer> computeVisibleChunks(List<ChunkBounds> chunks,
                                               float cameraX, float cameraY, float cameraZ) {
        // 返回可见区块索引列表
        return List.of();
    }
}
```

### PostProcessor

实现 `PostProcessor` 接口以添加自定义后处理效果：

```java
public class MyEffect implements PostProcessor {
    @Override
    public String getName() {
        return "MyEffect";
    }

    @Override
    public int getOrder() {
        return 500; // 执行顺序
    }

    @Override
    public void process(long commandBuffer, TextureInputs inputs,
                       TextureOutput output, int width, int height) {
        // 应用自定义后处理
    }
}
```

## API 文档

### 核心类

- `RenderiumCore` - 所有扩展和渲染技术的中心管理器
- `RenderExtension` - 扩展点接口
- `FrustumCuller` - 自定义剔除接口
- `PostProcessor` - 后处理效果接口

### 超分辨率

- `SuperResolutionManager` - 管理 DLSS/FSR/XeSS 技术
- `DLSSAdapter` - NVIDIA DLSS 集成
- `FSRAdapter` - AMD FSR 集成
- `XeSSAdapter` - Intel XeSS 集成

### 帧生成

- `FrameGeneratorManager` - 管理帧生成技术
- `DLSSFGAdapter` - DLSS 帧生成
- `FSRFGAdapter` - FSR 帧生成

### Reflex 低延迟

- `ReflexManager` - NVIDIA Reflex 低延迟模式

### 剔除

- `CullingController` - 协调多种剔除策略
- `BfsOcclusion` - 基于 BFS 的遮挡剔除

### Streamline SDK

- `SLContext` - Streamline SDK 上下文管理
- `VulkanStreamlineBridge` - Vulkan-Streamline 集成

### 配置

- `RenderiumConfig` - 主配置类
- 配置文件位置：`<游戏目录>/config/renderium.properties`

## 双模式系统

### 独立模式
独立运行，无需 Sodium，可使用完整功能集。

### 兼容模式（有 Sodium）
通过 Mixin 注入扩展 Sodium 设置界面，动态链接模式，兼容其他模组生态。

模式检测：
```java
RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
if (dualMode.isPerformanceModPresent()) {
    // Sodium 存在，运行在兼容模式
}
```

## 与 Minecraft 26.2 集成

Minecraft 26.2+ 包含官方 Vulkan 支持。Renderium 通过以下方式扩展它：

1. **Mixin 注入**：在关键点拦截 Minecraft 的渲染管线
2. **扩展回调**：在适当时机通知已注册的扩展
3. **资源访问**：提供对 Vulkan 纹理和命令缓冲区的访问
4. **管线扩展**：允许插入自定义渲染通道

## 调试模式

使用 JVM 参数启用调试日志：

```bash
# 基本调试输出
-Drenderium.debug=true

# 详细跟踪
-Drenderium.debug.verbose=true
```

## 许可证与合规

本项目采用 **MIT 许可证** - 详见 [LICENSE](LICENSE) 文件。

### 第三方组件

| 组件 | 许可证 | 用途 |
|------|--------|------|
| NVIDIA Streamline SDK | MIT 许可证 | DLSS/超分辨率核心框架 |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs 许可 | DLSS 超分辨率和帧生成 |
| LWJGL 3 | BSD 许可证 | Java 原生绑定 |
| FastUtil | Apache 2.0 | 高性能集合 |
| Sodium（可选） | LGPL-3.0 许可证 | 可选运行时依赖，通过 Mixin 动态链接 |

### 许可证合规声明

- **Streamline SDK**：以原版、未修改形式分发，包含完整版权声明
- **DLSS/DLSS-G**：受 NVIDIA RTX SDKs 许可约束，作为具有实质功能的应用程序分发
- **Sodium**：可选依赖，非衍生作品。LGPL-3.0 第 4 节允许动态链接

## 免责声明

本模组与 Mojang Studios 或 Microsoft 无关。  
本模组与 NVIDIA Corporation 无官方关联，DLSS 为 NVIDIA 的商标。
