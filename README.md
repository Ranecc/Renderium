# Renderium

现代 Minecraft 渲染扩展，支持 Vulkan、DLSS 和高级剔除。

## 特性

- **扩展点**：扩展 Minecraft 26.2+ 官方 Vulkan 渲染器
- **DLSS 支持**：NVIDIA DLSS 4、帧生成和替代超分辨率技术（XeSS、FSR）
- **高级剔除**：视锥剔除、遮挡剔除和距离剔除
- **自定义后处理**：将自定义后处理效果插入渲染管线
- **双平台**：同时支持 NeoForge 和 Fabric

### 双模式说明

- **独立模式**（无 Sodium）：独立运行
- **兼容模式**（有 Sodium）：通过 Mixin 注入扩展 Sodium 设置界面，动态链接模式，兼容其他模组生态

## 环境要求

- Java 25+
- Minecraft 26.2+
- NeoForge 26.2+ 或 Fabric Loader 0.18.4+
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU（用于超分辨率）
- Vulkan 兼容 GPU 和驱动程序

## 项目结构

```
Renderium/
├── common/                  # 平台无关核心
│   └── src/main/java/com/renderium/
│       ├── api/             # 扩展接口
│       ├── core/            # 核心管理器
│       ├── dlss/            # DLSS 集成
│       └── culling/         # 剔除算法
├── fabric/                  # Fabric 专属实现
│   └── src/main/java/com/renderium/fabric/
│       ├── FabricEntry.java
│       └── mixin/
└── neoforge/                # NeoForge 专属实现
    └── src/main/java/com/renderium/neoforge/
        ├── NeoForgeEntry.java
        └── mixin/
```

## 环境搭建

### 1. 安装依赖

确保你已安装：
- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+（或使用包含的 wrapper）

### 2. 编译

```bash
# 生成 Gradle wrapper
gradle wrapper --gradle-version=9.4.0

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

实现 `RenderExtension` 以添加自定义渲染功能：

```java
public class MyExtension implements RenderExtension {
    @Override
    public String getName() {
        return "MyExtension";
    }

    @Override
    public void onFrameBegin(int frameNumber, float deltaTime) {
        // 每帧开始时调用
    }

    @Override
    public void onOpaquePassRendered(long commandBuffer, long depthTexture, long colorTexture) {
        // 在不透明渲染之后、后处理之前调用
    }
}
```

注册你的扩展：

```java
RenderiumCore.getInstance().registerExtension(new MyExtension());
```

### FrustumCuller

实现 `FrustumCuller` 以使用自定义剔除算法：

```java
public class MyCuller implements FrustumCuller {
    @Override
    public void initialize(int maxDrawDistance) {
        // 初始化剔除资源
    }

    @Override
    public boolean isVisible(float minX, float minY, float minZ,
                            float maxX, float maxY, float maxZ) {
        // 自定义可见性测试
        return true;
    }
}
```

### PostProcessor

实现 `PostProcessor` 以添加自定义后处理效果：

```java
public class MyEffect implements PostProcessor {
    @Override
    public String getName() {
        return "MyEffect";
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

- `RenderiumCore` - 所有扩展的中心管理器
- `RenderExtension` - 扩展点接口
- `FrustumCuller` - 自定义剔除接口
- `PostProcessor` - 后处理效果接口

### DLSS 集成

- `DLSSManager` - 管理 DLSS 和超分辨率技术
- `DLSSMode` - DLSS 操作模式
- `DLSSQuality` - 质量预设

### 剔除

- `CullingController` - 协调多种剔除策略
- `CullingStrategy` - 剔除算法的基础接口
- `FrustumCullingStrategy` - 默认视锥剔除
- `DistanceCullingStrategy` - 基于距离的剔除

## 与 Minecraft 26.2 集成

Minecraft 26.2+ 包含官方 Vulkan 支持。Renderium 通过以下方式扩展它：

1. **Mixin 注入**：在关键点拦截 Minecraft 的渲染管线
2. **扩展回调**：在适当时机通知已注册的扩展
3. **资源访问**：提供对 Vulkan 纹理和命令缓冲区的访问
4. **管线扩展**：允许插入自定义渲染通道

## 路线图

- [x] 项目结构搭建
- [x] 核心 API 设计
- [x] 扩展接口
- [ ] Mixin 实现
- [ ] DLSS 集成（NVIDIA Streamline SDK）
- [ ] 高级剔除算法
- [ ] 后处理管线
- [ ] 性能优化

## 许可证与合规

本项目采用 **MIT 许可证** - 详见 [LICENSE](LICENSE) 文件。

### 第三方组件

本模组包含以下第三方软件组件：

| 组件 | 许可证 | 用途 |
|------|--------|------|
| NVIDIA Streamline SDK | MIT 许可证 | DLSS/超分辨率核心框架 |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs 许可 | DLSS 超分辨率和帧生成 |
| LWJGL 3 | BSD 许可证 | Java 原生绑定 |
| FastUtil | Apache 2.0 | 高性能集合 |
| Sodium (可选依赖) | LGPL-3.0 许可证 | 可选的运行时依赖，通过 Mixin 动态链接 |

### 许可证合规声明

本模组遵守所有第三方组件的许可证要求：

- **Streamline SDK**：DLL 文件以原版、未修改形式分发，完整版权和许可声明见 [THIRD-PARTY-NOTICES.md](renderium/common/src/main/resources/THIRD-PARTY-NOTICES.md)
- **DLSS/DLSS-G**：受 NVIDIA RTX SDKs 许可约束，本模组作为具有实质功能的应用程序分发这些组件
- **Sodium**（可选依赖）：本模组与 Sodium 为可选依赖关系，非衍生作品。Vulkan 满血模式下与 Sodium 零接触；兼容模式下通过 Mixin 注入扩展 Sodium 设置界面，属于 LGPL-3.0 第 4 节明确允许的"动态链接"模式。本模组代码完全独立编写，不包含任何 Sodium 源码。详见：https://github.com/CaffeineMC/sodium-fabric
- 所有 DLL 文件均为 NVIDIA 官方原版，未经任何修改或反向工程

完整第三方许可声明请查看：
- 源码仓库：[THIRD-PARTY-NOTICES.md](renderium/common/src/main/resources/THIRD-PARTY-NOTICES.md)
- 模组 JAR 内：`META-INF/THIRD-PARTY-NOTICES.md`

## 免责声明

本模组与 Mojang Studios 或 Microsoft 无关。  
本模组与 NVIDIA Corporation 无官方关联，DLSS 为 NVIDIA 的商标。
