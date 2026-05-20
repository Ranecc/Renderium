# Renderium

现代 Minecraft 渲染扩展，支持 Vulkan、DLSS 和高级优化技术。

> **开发状态：早期开发 / Early Development**
>
> 本项目处于活跃开发阶段，大量代码为骨架实现或占位代码。许多声明的功能尚未完全实现或仅在模拟状态下工作。

---

## 特性

- **扩展点**：设计用于扩展 Minecraft 26.2+ 官方 Vulkan 渲染器
- **超分辨率**：DLSS / XeSS / FSR 适配器框架（通过 NVIDIA Streamline SDK）
- **帧生成**：DLSS Frame Generation 和 FSR Frame Generation 适配器框架
- **高级剔除**：视锥剔除、BFS 遮挡剔除框架、HiZ 管理
- **自定义后处理**：着色器系统与后处理管线框架
- **双平台**：Fabric（已启用）/ NeoForge（计划中）
- **双模式**：独立模式和 Sodium 兼容模式（框架层）
- **C++ 加速**：可选原生库接口（预留）

## 环境要求

- Java 25+
- Minecraft 26.2-snapshot-3
- Fabric Loader 0.18.5+
- Vulkan 兼容 GPU 和驱动程序
- NVIDIA RTX / AMD RDNA2+ / Intel Arc GPU（用于超分辨率功能，需 Streamline SDK）

> **NeoForge 说明**：由于 NeoForge 21.x 暂不支持 Minecraft 26.2-snapshot，NeoForge 模块当前已禁用，待上游支持后启用。

## 项目结构

```
Renderium/
├── common/                          # 平台无关核心
│   └── src/main/java/com/ranecc/renderium/
│       ├── application/             # 应用层（核心生命周期管理）
│       ├── domain/                  # 领域层（枚举、配置模型）
│       ├── feature/                 # 功能模块层
│       │   ├── blaze3d/            # Blaze3D 优化器框架
│       │   ├── culling/            # 剔除系统框架
│       │   ├── intercept/          # 渲染拦截层
│       │   ├── pipeline/           # 渲染管线框架
│       │   └── shader/             # 着色器系统框架
│       ├── infrastructure/          # 基础设施层
│       │   ├── config/             # 配置管理
│       │   ├── gpu/                # GPU 资源管理
│       │   ├── nativeLib/          # 原生库 FFI 绑定
│       │   └── vulkan/             # Vulkan 辅助工具
│       ├── presentation/            # 表现层（UI 系统）
│       ├── tech/                    # 技术集成层
│       │   ├── dlss/               # DLSS 集成框架
│       │   ├── framegen/           # 帧生成框架
│       │   ├── reflex/             # Reflex 低延迟框架
│       │   ├── streamline/         # Streamline SDK 绑定
│       │   └── superres/           # 超分辨率适配器
│       └── platform/               # 平台抽象层
├── fabric/                          # Fabric 专属实现
└── neoforge/                        # NeoForge 专属实现（暂禁用）
```

## 环境搭建

### 1. 安装依赖

- JDK 25+ (https://adoptium.net/)
- Gradle 9.4+（或使用包含的 wrapper）

### 2. 编译

```bash
# 编译所有模块
./gradlew build

# 仅编译 Fabric
./gradlew :fabric:build
```

### 3. 运行开发客户端

```bash
./gradlew :fabric:runClient
```

## 核心 API

### RenderiumCore

中央管理器，提供生命周期控制：

```java
RenderiumCore core = RenderiumCore.getInstance();
core.initialize(deviceHandle);      // 初始化
core.processFrame(deltaTime);       // 帧处理
core.shutdown();                    // 关闭
```

### 配置

- 主配置类：`RenderiumConfig`
- 配置文件位置：`<游戏目录>/config/renderium.properties`

## 双模式系统

### 独立模式
独立运行，无需 Sodium。

### 兼容模式（有 Sodium）
通过 Mixin 注入扩展 Sodium 设置界面（框架预留）。

```java
RenderiumDualModeManager dualMode = RenderiumDualModeManager.getInstance();
if (dualMode.isPerformanceModPresent()) {
    // Sodium 检测（当前为占位实现）
}
```

## 调试模式

使用 JVM 参数启用调试日志：

```bash
# 基本调试输出
-Drenderium.debug=true

# 详细跟踪
-Drenderium.debug.verbose=true
```

## AI 辅助声明与开发状态

**本项目大量使用 AI 辅助开发。** 代码库中存在以下情况：

- **骨架代码**：大量方法为占位实现，直接返回 `true`/`false`/`null`
- **硬编码值**：FFM 结构体偏移量、分辨率缩放比例等
- **未实现功能**：模组检测、原生加速库、部分 Streamline SDK 调用链
- **接口漂移**：README 历史版本中的部分 API 示例与实际代码不匹配

详见 [骨架与缺失清单](.context/Renderium/skeleton-and-gaps.md)。

如发现问题，欢迎提交 Issue 或 Pull Request 指正。特别欢迎关于兼容性的 Issue（模组兼容、Minecraft 版本兼容、显卡/驱动兼容等）。

## 许可证与合规

本项目采用 **MIT 许可证** - 详见 [LICENSE](LICENSE) 文件。

### 第三方组件

| 组件 | 许可证 | 用途 |
|------|--------|------|
| NVIDIA Streamline SDK | MIT 许可证 | DLSS/超分辨率核心框架 |
| NVIDIA DLSS SDK | NVIDIA RTX SDKs 许可 | DLSS 超分辨率和帧生成 |
| LWJGL 3 | BSD 许可证 | Java 原生绑定 |
| FastUtil | Apache 2.0 | 高性能集合 |
| Sodium（可选） | LGPL-3.0 许可证 | 可选运行时依赖，动态链接 |

## 免责声明

本模组与 Mojang Studios 或 Microsoft 无关。  
本模组与 NVIDIA Corporation 无官方关联，DLSS 为 NVIDIA 的商标。
