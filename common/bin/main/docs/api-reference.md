# Blaze3D 拦截层系统 API 参考文档 (API Reference)

> **版本**: 5.1.0
> **最后更新**: 2026-04-20
> **适用范围**: Renderium v5.1+
> **包路径**: `com.renderium.interception`, `com.renderium.config`

---

## 目录

1. [核心接口 API](#1-核心接口-api)
2. [配置系统 API](#2-配置系统-api)
3. [数据类 API](#3-数据类-api)
4. [协调器 API](#4-协调器-api)
5. [工具类 API](#5-工具类-api)
6. [使用示例](#6-使用示例)

---

## 1. 核心接口 API

### 1.1 PreBlaze3DInterceptor 接口

**完整限定名**: `com.renderium.interception.PreBlaze3DInterceptor`
**源文件位置**: [`PreBlaze3DInterceptor.java`](../java/com/renderium/interception/PreBlaze3DInterceptor.java)
**接口类型**: 公共接口 (Public Interface)
**线程安全要求**: 实现类应保证线程安全，`intercept()` 方法应在渲染线程调用

#### 1.1.1 方法列表

| 方法签名 | 返回类型 | 说明 | 线程安全 |
|----------|----------|------|----------|
| `intercept(RenderContext context)` | `InterceptionResult` | 执行前拦截操作（核心方法） | ✅ 渲染线程 |
| `isModDetected()` | `boolean` | 检测是否识别到第三方模组 | ✅ |
| `registerModHandler(String modId, ModOutputHandler handler)` | `void` | 注册模组处理器 | ⚠️ 需外部同步 |
| `injectLOD(RenderContext context)` | `boolean` | 注入 LOD 预处理逻辑 | ✅ 渲染线程 |
| `injectCulling(RenderContext context)` | `boolean` | 注入剔除优化逻辑 | ✅ 渲染线程 |

#### 1.1.2 详细方法说明

##### `intercept(RenderContext context)`

```java
/**
 * 执行前拦截操作（核心方法）
 *
 * 这是前拦截层的主入口点，在 Blaze3D 渲染管线执行前被调用。
 * 负责协调所有前置拦截操作：
 *   1. 模组输出检测与处理
 *   2. LOD 预处理注入
 *   3. 剔除优化注入
 *   4. 渲染状态捕获与转换
 *
 * @param context 渲染上下文（包含相机、模组、资源等信息）
 *                - 必须非 null
 *                - 包含完整的渲染状态信息（View Matrix, Projection Matrix, Camera Position 等）
 * @return 拦截结果（包含修改后的上下文、性能指标、状态信息）
 *         - 成功时：result.isSuccess() == true, result.getModifiedContext() 包含修改后的上下文
 *         - 失败时：result.getStatus() == InterceptionResult.Status.FAILURE
 * @throws IllegalArgumentException 如果 context 为 null
 *
 * @see InterceptionResult
 * @see RenderContext
 *
 * 使用示例:
 * <pre>
 * // 在 MixinRenderSystem.renderLevel() 头部或 flipFrame() 之前：
 * RenderContext context = buildRenderContext();
 * InterceptionResult result = preInterceptor.intercept(context);
 *
 * if (result.isSuccess()) {
 *     // 使用修改后的上下文继续渲染
 *     context = result.getModifiedContext();
 *     // 记录性能指标
 *     long elapsedNanos = result.getElapsedTimeNanos();
 * } else if (result.getStatus() == InterceptionResult.Status.FAILURE) {
 *     // 回退到原始路径
 *     return originalRendering(context);
 * }
 * </pre>
 */
InterceptionResult intercept(RenderContext context);
```

**参数详解**:

| 参数名 | 类型 | 是否必填 | 说明 |
|--------|------|----------|------|
| `context` | `RenderContext` | ✅ 是 | 渲染上下文对象，包含当前帧的所有状态信息 |

**返回值详解**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | `boolean` | 操作是否成功完成 |
| `modifiedContext` | `RenderContext` | 经过前拦截处理的修改后上下文（可能包含 LOD 数据、剔除掩码等） |
| `status` | `InterceptionResult.Status` | 枚举值：SUCCESS / PARTIAL / FAILURE |
| `elapsedTimeNanos` | `long` | 本次拦截操作的耗时（纳秒），用于性能监控 |
| `modDetectionResult` | `ModDetectionResult` | 模组检测结果详情 |
| `performanceMetrics` | `Map<String, Object>` | 各阶段的性能指标（可选） |

**异常情况**:

| 异常类型 | 触发条件 | 处理建议 |
|----------|----------|----------|
| `IllegalArgumentException` | `context == null` | 检查参数，确保传入有效的 RenderContext |
| `IllegalStateException` | 拦截器未初始化 | 调用 `initialize()` 方法后再使用 |

---

##### `isModDetected()`

```java
/**
 * 检测是否识别到第三方模组
 *
 * 通过 ClassPath 扫描和版本字符串匹配来检测已安装的模组，
 * 包括 Sodium、Iris、Oculus 等。此方法在初始化时缓存结果，
 * 后续调用直接返回缓存值以保证性能。
 *
 * @return true 如果检测到支持的模组（Sodium/Iris/OC 等）
 *         false 如果未检测到任何支持的模组
 *
 * 使用示例:
 * <pre>
 * if (preInterceptor.isModDetected()) {
 *     LOGGER.info("检测到第三方模组，启用兼容模式");
 *     enableCompatibilityMode();
 * } else {
 *     LOGGER.info("纯净环境，可使用全部优化功能");
 * }
 * </pre>
 */
boolean isModDetected();
```

---

##### `registerModHandler(String modId, ModOutputHandler handler)`

```java
/**
 * 注册模组处理器
 *
 * 用于扩展对新的第三方模组的支持。注册后，当检测到对应模组时，
 * 会自动调用该处理器进行输出重定向。
 *
 * @param modId    模组 ID（如 "sodium", "iris", "oculus"）
 *                 - 必须非 null 且非空字符串
 *                 - 应使用小写字母
 * @param handler  处理器实例（实现 ModOutputHandler 接口）
 *                 - 必须非 null
 *                 - 负责具体的模组输出重定向逻辑
 * @throws IllegalArgumentException 如果 modId 或 handler 为 null
 *
 * 使用示例:
 * <pre>
 * // 注册自定义模组处理器
 * preInterceptor.registerModHandler("my-custom-mod", new CustomModHandler());
 *
 * // 内置处理器示例
 * preInterceptor.registerModHandler("sodium", new SodiumOutputHandler());
 * </pre>
 */
void registerModHandler(String modId, ModOutputHandler handler);
```

**参数详解**:

| 参数名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `modId` | `String` | 非空，小写 | 模组的唯一标识符 |
| `handler` | `ModOutputHandler` | 非 null | 模组输出处理器实现 |

---

##### `injectLOD(RenderContext context)`

```java
/**
 * 注入 LOD 预处理逻辑
 *
 * 根据当前相机位置和 Chunk 分布，计算每个 Chunk 的 LOD 等级，
 * 并将结果注入到渲染上下文中供后续使用。
 *
 * @param context 渲染上下文（必须包含有效的相机信息）
 * @return true 如果成功注入 LOD 数据
 *         false 如果注入失败（如相机信息无效）
 *
 * 性能影响:
 * - CPU 模式: ~0.3ms/frame
 * - GPU Driven 模式: ~0.05ms/frame (仅提交 Compute Shader)
 *
 * 使用示例:
 * <pre>
 * boolean success = preInterceptor.injectLOD(renderContext);
 * if (success) {
 *     Map<ChunkPos, Integer> lodLevels = renderContext.getLODData();
 *     lodLevels.forEach((pos, level) ->
 *         LOGGER.debug("Chunk {} -> LOD{}", pos, level)
 *     );
 * }
 * </pre>
 */
boolean injectLOD(RenderContext context);
```

---

##### `injectCulling(RenderContext context)`

```java
/**
 * 注入剔除优化逻辑
 *
 * 执行视锥体剔除、遮挡剔除和距离剔除，生成可见对象列表，
 * 并将剔除掩码注入到渲染上下文中。
 *
 * @param context 渲染上下文（必须包含视锥体和场景数据）
 * @return true 如果成功注入剔除数据
 *         false 如果注入失败
 *
 * 性能影响:
 * - 视锥体剔除: ~0.1ms/frame
 * - 遮挡剔除: ~0.2ms/frame (Hi-Z 4x4)
 * - 距离剔除: ~0.05ms/frame
 * 总计: ~0.35-0.5ms/frame
 *
 * 使用示例:
 * <pre>
 * boolean success = preInterceptor.injectCulling(renderContext);
 * if (success) {
 *     CullingResult culling = renderContext.getCullingResult();
 *     LOGGER.info("剔除统计: 输入={}, 可见={}, 移除={}",
 *                 culling.getInputCount(),
 *                 culling.getVisibleCount(),
 *                 culling.getCulledCount());
 * }
 * </pre>
 */
boolean injectCulling(RenderContext context);
```

---

### 1.2 PostBlaze3DInterceptor 接口

**完整限定名**: `com.renderium.interception.PostBlaze3DInterceptor`
**源文件位置**: [`PostBlaze3DInterceptor.java`](../java/com/renderium/interception/PostBlaze3DInterceptor.java)
**接口类型**: 公共接口 (Public Interface)
**线程安全要求**: 实现类应保证线程安全，特别是在 Triple Buffering 场景下

#### 1.2.1 方法列表

| 方法签名 | 返回类型 | 说明 | 线程安全 |
|----------|----------|------|----------|
| `postProcess(FrameData frameData)` | `InterceptedFrameData` | 后处理入口方法（核心） | ✅ 渲染线程 |
| `captureFrame(CaptureMethod method)` | `FrameData` | 捕获当前帧 | ✅ 渲染线程 |
| `applySuperResolution(...)` | `FrameData` | 应用超分辨率处理 | ✅ 渲染线程 |
| `applyFrameGeneration(...)` | `FrameData` | 应用帧生成 | ✅ 渲染线程 |
| `applyPostProcessing(...)` | `FrameData` | 应用后处理效果链 | ✅ 渲染线程 |
| `outputToScreen(...)` | `void` | 输出到屏幕 | ✅ 渲染线程 |

#### 1.2.2 核心方法详细说明

##### `postProcess(FrameData frameData)`

```java
/**
 * 后处理入口方法（核心）
 *
 * 这是后拦截层的主入口点，在 Blaze3D 渲染完成后被调用。
 * 协调所有后处理操作：
 *   1. 帧捕获（FBO 或 Swapchain Image）
 *   2. 超分辨率处理（DLSS/FSR/XeSS）
 *   3. 帧生成（DLSS-FG/FSR-FG）
 *   4. EffectPipeline 后处理（Bloom/DOF/MotionBlur）
 *   5. 输出呈现到屏幕
 *
 * @param frameData 从 Blaze3D 捕获的原始帧数据
 *                   - 必须非 null
 *                   - 包含有效像素数据和元数据
 * @return 处理后的最终帧数据（InterceptedFrameData）
 *         - 包含 HDR 数据（如果启用）
 *         - 包含质量指标（SSIM、PSNR 等）
 *         - 包含性能元数据（各阶段耗时）
 *         如果处理失败则返回 null 或原始 frameData
 * @throws IllegalArgumentException 如果 frameData 为 null
 *
 * 使用示例:
 * <pre>
 * // 在 MixinRenderSystem.flipFrame() 中：
 * FrameData rawFrame = captureRawFrame();
 *
 * // 调用后拦截层
 * InterceptedFrameData finalFrame = postInterceptor.postProcess(rawFrame);
 *
 * if (finalFrame != null) {
 *     // 获取增强后的帧数据
 *     ByteBuffer pixelBuffer = finalFrame.getPixelData();
 *     int width = finalFrame.getWidth();
 *     int height = finalFrame.getHeight();
 *
 *     // 可选：检查质量指标
 *     QualityMetrics metrics = finalFrame.getQualityMetrics();
 *     LOGGER.debug("Quality: SSIM={}, PSNR={}",
 *                  metrics.getSSIM(), metrics.getPSNR());
 *
 *     // 显示到屏幕
 *     presentToScreen(finalFrame);
 * } else {
 *     // 处理失败，回退到原始流程
 *     presentToScreen(rawFrame);
 * }
 * </pre>
 */
InterceptedFrameData postProcess(FrameData frameData);
```

**参数详解**:

| 参数名 | 类型 | 约束 | 说明 |
|--------|------|------|------|
| `frameData` | `FrameData` | 非 null | 从 Blaze3D 捕获的原始帧数据 |

**返回值详解** (`InterceptedFrameData`):

| 字段 | 类型 | 说明 |
|------|------|------|
| `pixelData` | `ByteBuffer` | 最终像素数据（可能是 HDR 格式） |
| `width` | `int` | 帧宽度（像素） |
| `height` | `int` | 帧高度（像素） |
| `format` | `PixelFormat` | 像素格式（RGBA8/RGBA16F/RGBA32F） |
| `qualityMetrics` | `QualityMetrics` | 质量评估指标 |
| `metadata` | `Map<String, Object>` | 扩展元数据（各阶段耗时等） |
| `hdrMetadata` | `HDRMetadata` | HDR 相关元数据（如果适用） |

**异常情况**:

| 异常类型 | 触发条件 | 处理建议 |
|----------|----------|----------|
| `IllegalArgumentException` | `frameData == null` | 检查参数有效性 |
| `IllegalStateException` | Streamline SDK 未初始化 | 先调用 `StreamlineSDK.initialize()` |
| `OutOfMemoryError` | GPU 内存不足 | 降低分辨率或禁用部分功能 |

---

## 2. 配置系统 API

### 2.1 InterceptionConfig 类

**完整限定名**: `com.renderium.config.RenderiumConfig.InterceptionConfig`
**源文件位置**: [`RenderiumConfig.java`](../java/com/renderium/config/RenderiumConfig.java) (内部类)
**类型**: 静态内部类 (Static Inner Class)
**用途**: 拦截层总配置，管理前后拦截层和 Sodium 重定向器的所有配置项

#### 2.1.1 构造函数

```java
/**
 * 默认构造函数 - 使用 spec.md 定义的默认值
 *
 * 默认值参考:
 * - preInterceptor.enabled = true
 * - postInterceptor.enabled = true
 * - sodiumRedirector.enabled = true
 * - 其他子配置项详见各自类的默认构造函数
 */
public InterceptionConfig()
```

#### 2.1.2 工厂方法

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `fromProperties(Properties props)` | `InterceptionConfig` | 从 Properties 对象加载配置 |
| `toProperties(Properties props)` | `void` | 将配置写入 Properties 对象 |
| `validate()` | `ValidationResult` | 验证配置的有效性 |

##### `fromProperties(Properties props)`

```java
/**
 * 从 Properties 对象加载拦截层配置
 *
 * @param props 属性集合，键前缀为 "interception."
 *              - 必须非 null
 *              - 缺失的字段将使用默认值填充
 * @return InterceptionConfig 实例（包含加载的配置或默认值）
 *
 * 支持的键格式:
 * interception.preInterceptor.enabled=true
 * interception.preInterceptor.lodInjection.maxLevels=4
 * interception.postInterceptor.superResolution.renderScale=0.667
 * interception.sodiumRedirector.fallbackMode=safe
 * ...
 *
 * 使用示例:
 * <pre>
 * Properties props = new Properties();
 * try (Reader reader = Files.newBufferedReader(configFile)) {
 *     props.load(reader);
 * }
 * InterceptionConfig config = InterceptionConfig.fromProperties(props);
 * </pre>
 */
public static InterceptionConfig fromProperties(Properties props)
```

##### `validate()`

```java
/**
 * 验证拦截层配置的有效性
 *
 * 验证规则包括:
 * - 所有枚举值必须在允许范围内
 * - 数值范围检查（如 renderScale ∈ [0.5, 1.0]）
 * - 数组递增验证（distanceThresholds 必须严格递增）
 * - 性能警告（如低 renderScale + quality mode 可能导致性能问题）
 *
 * @return 验证结果（OK/WARNING/ERROR）
 *         - OK: 所有配置合法
 *         - WARNING: 合法但有潜在性能风险
 *         - ERROR: 存在非法配置值
 *
 * 使用示例:
 * <pre>
 * ValidationResult result = config.validate();
 * switch (result.getLevel()) {
 *     case OK:
 *         LOGGER.info("配置验证通过");
 *         break;
 *     case WARNING:
 *         LOGGER.warn("配置警告: {}", result.getMessage());
 *         break;
 *     case ERROR:
 *         throw new ConfigurationException("配置错误: " + result.getMessage());
 * }
 * </pre>
 */
public ValidationResult validate()
```

#### 2.1.3 Getter/Setter 方法

| 方法签名 | 返回类型 | 说明 |
|----------|----------|------|
| `getPreInterceptor()` | `PreInterceptorConfig` | 获取前拦截层配置 |
| `setPreInterceptor(PreInterceptorConfig)` | `void` | 设置前拦截层配置 |
| `getPostInterceptor()` | `PostInterceptorConfig` | 获取后拦截层配置 |
| `setPostInterceptor(PostInterceptorConfig)` | `void` | 设置后拦截层配置 |
| `getSodiumRedirector()` | `SodiumRedirectorConfig` | 获取 Sodium 重定向器配置 |
| `setSodiumRedirector(SodiumRedirectorConfig)` | `void` | 设置 Sodium 重定向器配置 |

---

### 2.2 PreInterceptorConfig 类

**完整限定名**: `com.renderium.config.RenderiumConfig.PreInterceptorConfig`
**父类**: 无（顶层静态内部类）

#### 2.2.1 配置字段

| 字段名 | 类型 | 默认值 | 范围/约束 | 说明 |
|--------|------|--------|-----------|------|
| `enabled` | `boolean` | `true` | - | 是否启用前拦截层 |
| `sodiumDetection` | `boolean` | `true` | - | 是否启用 Sodium 模组检测 |
| `lodInjection` | `LODConfig` | `new LODConfig()` | - | LOD 注入配置（见下文） |
| `cullingInjection` | `CullingInjectionConfig` | `new CullingInjectionConfig()` | - | 剔除注入配置（见下文） |

#### 2.2.2 使用示例

```java
// 获取并修改前拦截层配置
InterceptionConfig interception = config.getInterceptionConfig();
PreInterceptorConfig preConfig = interception.getPreInterceptor();

// 启用/禁用前拦截层
preConfig.setEnabled(true);

// 启用 Sodium 检测
preConfig.setSodiumDetection(true);

// 配置 LOD
preConfig.getLodInjection().setMaxLevels(4);
preConfig.getLodInjection().setTransitionMode("dithering");

// 配置剔除策略
preConfig.getCullingInjection().setStrategy("balanced");
preConfig.getCullingInjection().setOcclusionCulling(true);

// 验证配置
ValidationResult result = preConfig.validate();
if (!result.isOk()) {
    LOGGER.error("前拦截层配置错误: " + result.getMessage());
}
```

---

### 2.3 PostInterceptorConfig 类

**完整限定名**: `com.renderium.config.RenderiumConfig.PostInterceptorConfig`

#### 2.3.1 配置字段

| 字段名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | `boolean` | `true` | 是否启用后拦截层 |
| `frameCapture` | `FrameCaptureConfig` | `new FrameCaptureConfig()` | 帧捕获配置 |
| `superResolution` | `SuperResolutionConfig` | `new SuperResolutionConfig()` | 超分辨率配置 |
| `frameGeneration` | `FrameGenerationConfig` | `new FrameGenerationConfig()` | 帧生成配置 |
| `postProcessing` | `PostProcessingConfig` | `new PostProcessingConfig()` | 后处理配置 |

#### 2.3.2 关键子配置类

##### FrameCaptureConfig

| 字段名 | 类型 | 默认值 | 有效值 | 说明 |
|--------|------|--------|--------|------|
| `method` | `String` | `"auto"` | `"auto"`, `"fbo"`, `"swapchain"` | 帧捕获方式 |
| `format` | `String` | `"RGBA16F"` | `"RGBA8"`, `"RGBA16F"`, `"RGBA32F"` | 像素格式 |
| `msaa` | `int` | `4` | `0`, `2`, `4`, `8` | MSAA 采样数 |
| `async` | `boolean` | `false` | - | 是否异步捕获 |

##### SuperResolutionConfig

| 字段名 | 类型 | 默认值 | 有效值 | 说明 |
|--------|------|--------|--------|------|
| `enabled` | `boolean` | `true` | - | 是否启用超分辨率 |
| `preferredTechnology` | `String` | `"auto"` | `"auto"`, `"dlss"`, `"xess"`, `"fsr"` | 首选技术 |
| `qualityMode` | `String` | `"balanced"` | `"quality"`, `"balanced"`, `"performance"` | 质量模式 |
| `renderScale` | `float` | `0.667` | `[0.5, 1.0]` | 渲染比例（0.5=50%, 1.0=100%） |
| `sharpening` | `float` | `0.3` | `[0.0, 1.0]` | 锐化强度 |

##### FrameGenerationConfig

| 字段名 | 类型 | 默认值 | 有效值 | 说明 |
|--------|------|--------|--------|------|
| `enabled` | `boolean` | `false` | - | 是否启用帧生成（⚠️ 高风险） |
| `mode` | `String` | `"dlss-fg"` | `"dlss-fg"`, `"fsr-fg"` | 帧生成模式 |
| `targetFPS` | `int` | `120` | `[60, 240]` | 目标 FPS |

---

### 2.4 ConfigMigration 工具类

**完整限定名**: `com.renderium.config.ConfigMigration`
**源文件位置**: [`ConfigMigration.java`](../java/com/renderium/config/ConfigMigration.java)
**类型**: 工具类 (Utility Class)，不可实例化
**用途**: 处理配置文件版本迁移，确保向后兼容

#### 2.4.1 主要方法

##### `migrate(Path configDir)`

```java
/**
 * 执行配置迁移（主入口方法）
 *
 * 此方法会：
 * 1. 检查配置文件是否存在
 * 2. 读取当前配置版本
 * 3. 根据版本执行相应的迁移逻辑
 * 4. 更新版本号标记
 * 5. 保存迁移后的配置
 *
 * @param configDir 配置目录路径（如 Paths.get("config/renderium")）
 * @return 迁移结果（MigrationResult）
 *         - success: 迁移过程是否成功完成
 *         - message: 详细的迁移日志
 *         - migrated: 是否实际修改了配置文件
 *
 * 使用示例:
 * <pre>
 * // 在应用启动时调用
 * Path configDir = Paths.get("config/renderium");
 * MigrationResult result = ConfigMigration.migrate(configDir);
 *
 * if (result.isSuccess()) {
 *     if (result.wasMigrated()) {
 *         LOGGER.info("配置迁移成功:\n{}", result.getMessage());
 *     } else {
 *         LOGGER.info("配置已是最新版本");
 *     }
 * } else {
 *     LOGGER.error("配置迁移失败: {}", result.getMessage());
 *     // 可以选择退出或使用默认配置
 * }
 *
 * // 迁移完成后正常加载配置
 * RenderiumConfig config = RenderiumConfig.load(configDir);
 * </pre>
 */
public static MigrationResult migrate(Path configDir)
```

**返回值详解 (`MigrationResult`)**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | `boolean` | 迁移过程是否无错误完成 |
| `message` | `String` | 详细的迁移日志（包含所有新增/迁移/废弃的字段） |
| `migrated` | `boolean` | 是否实际修改了配置文件（false 表示无需迁移） |

**迁移日志示例**:

```
[ADD] interception.preInterceptor.enabled = true
[ADD] interception.preInterceptor.lodInjection.maxLevels = 4
[MIGRATE] superResolution.enabled → interception.postInterceptor.superResolution.enabled
[DEPRECATED] superResolution.technology is deprecated in v5.1
...
```

---

## 3. 数据类 API

### 3.1 InterceptionResult 类

**完整限定名**: `com.renderium.interception.InterceptionResult`
**类型**: 不可变数据类 (Immutable Data Class)
**用途**: 封装前拦截层的处理结果和性能指标

#### 3.1.1 枚举常量

```java
public enum Status {
    /** 拦截完全成功 */
    SUCCESS,
    /** 部分成功（某些步骤跳过） */
    PARTIAL,
    /** 拦截失败（需要回退到原始路径） */
    FAILURE
}
```

#### 3.1.2 Builder 模式使用

```java
// 使用 Builder 创建 InterceptionResult
InterceptionResult result = InterceptionResult.builder()
    .success(true)
    .status(InterceptionResult.Status.SUCCESS)
    .modifiedContext(modifiedRenderContext)
    .elapsedTimeNanos(System.nanoTime() - startTime)
    .modDetectionResult(modDetection)
    .build();

// 或者使用便捷方法
InterceptionResult okResult = InterceptionResult.success(modifiedContext, elapsedTimeNanos);
InterceptionResult failResult = InterceptionResult.failure("Failed to detect mods");
```

---

### 3.2 RenderContext 类

**完整限定名**: `com.renderium.interception.RenderContext`
**用途**: 渲染上下文，包含一帧渲染所需的所有状态信息

#### 3.2.2 关键字段

| 字段名 | 类型 | 说明 |
|--------|------|------|
| `cameraPosition` | `Vec3d` | 相机世界坐标 |
| `viewMatrix` | `Matrix4f` | 视图矩阵 |
| `projectionMatrix` | `Matrix4f` | 投影矩阵 |
| `frustum` | `Frustum` | 视锥体（用于剔除） |
| `visibleChunks` | `List<ChunkRenderData>` | 可见 Chunk 列表 |
| `lodData` | `Map<ChunkPos, Integer>` | LOD 等级映射（由前拦截层注入） |
| `cullingMask` | `BitSet` | 剔除掩码（由前拦截层注入） |
| `modInfoList` | `List<ModInfo>` | 检测到的模组列表 |
| `renderPasses` | `List<RenderPass>` | 渲染 Pass 列表 |

---

## 4. 协调器 API

### 4.1 BackendInterceptor 类

**完整限定名**: `com.renderium.backend.BackendInterceptor`
**设计模式**: 单例模式 (Singleton)
**线程安全**: 使用 AtomicBoolean 保证初始化安全

#### 4.1.1 单例访问

```java
/**
 * 获取全局唯一实例
 *
 * @return BackendInterceptor 实例（懒加载初始化）
 *
 * 使用示例:
 * <pre>
 * BackendInterceptor backend = BackendInterceptor.getInstance();
 * </pre>
 */
public static BackendInterceptor getInstance()
```

#### 4.1.2 核心方法

##### `executeFrame(RenderContext context)`

```java
/**
 * 执行完整的帧渲染流程（v5.1 双拦截层架构）
 *
 * 流程:
 * 1. 前拦截 → 2. Blaze3D 渲染 → 3. 后拦截
 *
 * @param context 渲染上下文
 * @return 处理后的最终帧数据
 *
 * 使用示例:
 * <pre>
 * BackendInterceptor backend = BackendInterceptor.getInstance();
 *
 * // 构建渲染上下文
 * RenderContext context = buildRenderContext(camera, chunks, ...);
 *
 * // 执行完整帧渲染
 * FrameData finalFrame = backend.executeFrame(context);
 *
 * // 输出到屏幕
 * if (finalFrame != null) {
 *     present(finalFrame);
 * }
 * </pre>
 */
public FrameData executeFrame(RenderContext context)
```

##### `getPreInterceptor()` / `getPostInterceptor()`

```java
/**
 * 获取前/后拦截层实例（v5.1 新增）
 *
 * @return 对应拦截层实例，如果未初始化或已禁用则返回 null
 *
 * 使用示例:
 * <pre>
 * PreBlaze3DInterceptor preInt = backend.getPreInterceptor();
 * if (preInt != null && preInt.isModDetected()) {
 *     // 执行特定于模组的处理
 * }
 * </pre>
 */
public PreBlaze3DInterceptor getPreInterceptor()
public PostBlaze3DInterceptor getPostInterceptor()
```

---

## 5. 工具类 API

### 5.1 ConfigConstants 常量类

**完整限定名**: `com.renderium.config.ConfigConstants`
**用途**: 定义配置相关的常量和默认值

#### 5.1.1 常量定义

```java
public final class ConfigConstants {

    /** 配置文件名 */
    public static final String CONFIG_FILE = "renderium.properties";

    /** 当前版本号 */
    public static final String CURRENT_VERSION = "5.1";

    /** 拦截层配置键前缀 */
    public static final String INTERCEPTION_PREFIX = "interception.";

    /** 默认渲染比例 */
    public static final float DEFAULT_RENDER_SCALE = 0.667f;

    /** 默认 MSAA 值 */
    public static final int DEFAULT_MSAA = 4;

    // ... 更多常量
}
```

---

## 6. 使用示例

### 6.1 完整集成示例

```java
package com.renderium.example;

import com.renderium.backend.BackendInterceptor;
import com.renderium.config.RenderiumConfig;
import com.renderium.config.ConfigMigration;
import com.renderium.interception.*;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Renderium 拦截层系统集成示例
 *
 * 展示如何在 Minecraft 模组的渲染管线中集成 Blaze3D 拦截层系统
 */
public class InterceptionIntegrationExample {

    private RenderiumConfig config;
    private BackendInterceptor backend;

    /**
     * 初始化拦截层系统（在游戏启动时调用）
     */
    public void initialize(Path configDir) {
        // 步骤 1: 执行配置迁移（自动处理旧版配置）
        ConfigMigration.MigrationResult migrationResult =
            ConfigMigration.migrate(configDir);

        if (!migrationResult.isSuccess()) {
            System.err.println("配置迁移失败: " + migrationResult.getMessage());
            // 可以选择使用默认配置继续运行
        }

        // 步骤 2: 加载配置
        this.config = RenderiumConfig.load(configDir);

        // 步骤 3: 验证配置
        RenderiumConfig.ValidationResult validationResult = config.validate();
        if (validationResult.hasErrors()) {
            throw new RuntimeException(
                "配置验证失败: " + validationResult.getMessage()
            );
        }

        // 步骤 4: 初始化协调器
        this.backend = BackendInterceptor.getInstance();
        backend.initialize(config);

        System.out.println("✓ 拦截层系统初始化完成");
        System.out.println("  前拦截层: " +
            (config.getInterceptionConfig().getPreInterceptor().isEnabled() ? "启用" : "禁用"));
        System.out.println("  后拦截层: " +
            (config.getInterceptionConfig().getPostInterceptor().isEnabled() ? "启用" : "禁用"));
    }

    /**
     * 执行帧渲染（每帧调用）
     *
     * @param camera 相机对象
     * @param chunks 待渲染的 Chunk 列表
     * @return 最终帧数据
     */
    public FrameData renderFrame(Camera camera, List<Chunk> chunks) {
        // 构建渲染上下文
        RenderContext context = buildRenderContext(camera, chunks);

        // 通过协调器执行完整帧渲染流程
        FrameData finalFrame = backend.executeFrame(context);

        return finalFrame;
    }

    /**
     * 动态调整配置（运行时热更新）
     *
     * 示例：根据 FPS 自动调整超分辨率质量
     */
    public void autoAdjustQuality(double currentFPS) {
        PostInterceptorConfig postConfig =
            config.getInterceptionConfig().getPostInterceptor();
        SuperResolutionConfig srConfig = postConfig.getSuperResolution();

        if (currentFPS < 30 && srConfig.getQualityMode().equals("quality")) {
            // FPS 过低，降级到 balanced 模式
            System.out.println("⚠ FPS过低 (" + currentFPS +
                ")，降级超分辨率质量至 balanced");
            srConfig.setQualityMode("balanced");

        } else if (currentFPS > 100 &&
                   srConfig.getQualityMode().equals("performance")) {
            // FPS 充裕，升级到 quality 模式
            System.out.println("✓ FPS充足 (" + currentFPS +
                ")，升级超分辨率质量至 quality");
            srConfig.setQualityMode("quality");
        }

        // 保存配置变更
        config.save(getConfigDirectory());
    }

    /**
     * 自定义模组处理器注册示例
     */
    public void registerCustomModHandlers() {
        PreBlaze3DInterceptor preInt = backend.getPreInterceptor();

        if (preInt != null) {
            // 注册自定义模组处理器
            preInt.registerModHandler("my-custom-mod", new MyCustomModHandler());

            System.out.println("✓ 已注册自定义模组处理器: my-custom-mod");
        }
    }

    /**
     * 性能监控示例
     */
    public void monitorPerformance() {
        // 定期打印性能指标（可通过调试界面显示）
        if (config.isDebugMode()) {
            PreInterceptorConfig preConfig =
                config.getInterceptionConfig().getPreInterceptorConfig();

            System.out.println("[性能监控]");
            System.out.println("  前拦截层耗时: " +
                getLastPreInterceptorTimeMs() + "ms");
            System.out.println("  后拦截层耗时: " +
                getLastPostInterceptorTimeMs() + "ms");
            System.out.println("  总拦截开销: " +
                getTotalOverheadMs() + "ms");
        }
    }

    // ==================== 辅助方法 ====================

    private RenderContext buildRenderContext(Camera camera, List<Chunk> chunks) {
        // 实现 RenderContext 构建
        // ...
        return new RenderContext(/* ... */);
    }

    private Path getConfigDirectory() {
        return Paths.get("config/renderium");
    }

    private double getLastPreInterceptorTimeMs() { /* ... */ }
    private double getLastPostInterceptorTimeMs() { /* ... */ }
    private double getTotalOverheadMs() { /* ... */ }
}

/**
 * 自定义模组处理器示例
 */
class MyCustomModHandler implements ModOutputHandler {

    @Override
    public boolean canHandle(String modId) {
        return "my-custom-mod".equals(modId);
    }

    @Override
    public void redirectOutput(RenderContext context) {
        // 实现模组特定的输出重定向逻辑
        System.out.println("正在重定向 my-custom-mod 的渲染输出...");
        // 1. 检测模组的渲染调用
        // 2. 截取 FBO 或 Vulkan Image
        // 3. 转换格式并注入到 Renderium 管线
    }

    @Override
    public String getSupportedVersionRange() {
        return "1.0.x - 2.0.x";
    }
}
```

### 6.2 配置读写示例

```java
// ===== 读取配置 =====
RenderiumConfig config = RenderiumConfig.load(Paths.get("config"));

// 访问拦截层配置
InterceptionConfig interception = config.getInterceptionConfig();

// 读取前拦截层配置
boolean preEnabled = interception.getPreInterceptor().isEnabled();
int maxLODLevels = interception.getPreInterceptor().getLodInjection().getMaxLevels();
String cullingStrategy = interception.getPreInterceptor().getCullingInjection().getStrategy();

// 读取后拦截层配置
float renderScale = interception.getPostInterceptor().getSuperResolution().getRenderScale();
boolean fgEnabled = interception.getPostInterceptor().getFrameGeneration().isEnabled();

// ===== 修改配置 =====
// 启用帧生成（高风险操作）
interception.getPostInterceptor().getFrameGeneration().setEnabled(true);
interception.getPostInterceptor().getFrameGeneration().setTargetFPS(144);

// 调整超分辨率设置
interception.getPostInterceptor().getSuperResolution().setPreferredTechnology("dlss");
interception.getPostInterceptor().getSuperResolution().setQualityMode("quality");

// ===== 验证并保存 =====
ValidationResult result = config.validate();
if (result.isOk()) {
    config.save(Paths.get("config"));
    System.out.println("✓ 配置已保存");
} else {
    System.err.println("✗ 配置验证失败: " + result.getMessage());
}
```

---

## 附录

### A. 异常类汇总

| 异常类 | 触发场景 | 建议 |
|--------|----------|------|
| `ConfigurationException` | 配置值非法 | 检查配置文件，修正非法值 |
| `InterceptionException` | 拦截过程出错 | 查看详细日志，检查依赖 |
| `ModDetectionException` | 模组检测失败 | 确认模组版本是否支持 |
| `FrameCaptureException` | 帧捕获失败 | 检查 FBO/Swapchain 状态 |
| `StreamlineException` | SDK 调用失败 | 确认 Streamline SDK 已正确安装 |

### B. 版本兼容性矩阵

| Renderium 版本 | 本 API 文档版本 | 兼容性 |
|----------------|-----------------|--------|
| 5.0 | ❌ 不兼容 | 缺少拦截层相关 API |
| **5.1** | ✅ **完全兼容** | 当前版本 |
| 5.2+ (未来) | ⚠️ 可能不兼容 | 可能添加新 API 或修改现有 API |

### C. 相关资源链接

- **架构文档**: [interception-architecture.md](./interception-architecture.md)
- **故障排查**: [troubleshooting.md](./troubleshooting.md)
- **技术规格**: [spec.md](../../../.trae/specs/build-blaze3d-interception-layers/spec.md)
- **源代码**: [`com.renderium.interception`](../java/com/renderium/interception/) 包
- **配置系统**: [`com.renderium.config`](../java/com/renderium/config/) 包

---

> 📝 **维护说明**: 本 API 文档应与代码保持同步更新。如发现 API 变更未及时反映在此文档中，请提交 Issue 或 PR。
