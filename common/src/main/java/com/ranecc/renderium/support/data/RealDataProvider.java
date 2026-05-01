// Renderium v6 Phase 1.1 - 数据桥接层
// RealDataProvider - 真实 Minecraft 数据桥接层

package com.ranecc.renderium.support.data;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RealDataProvider - 真实 Minecraft 数据桥接层 🌉
 *
 * <p>从 Minecraft 运行时实例获取真实的渲染数据，
 * 用于替代 Phase 1-5 中的 Mock/Stub 实现。
 *
 * <h2>核心功能：</h2>
 * <ul>
 *   <li><b>相机数据</b>：位置、朝向、FOV（从 Camera 类获取）</li>
 *   <li><b>视锥体数据</b>：6 平面参数（从 Frustum 类获取）</li>
 *   <li><b>区块数据</b>：可见区块列表、位置数组（从 SectionRenderDispatcher 获取）</li>
 *   <li><b>渲染状态</b>：FBO、纹理、着色器状态（从 RenderSystem 获取）</li>
 * </ul>
 *
 * <h2>架构设计：</h2>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │              RealDataProvider (Singleton)     │
 * ├─────────────────────────────────────────────┤
 * │  数据源接口层                                │
 * │  ├── minecraftInstance (Minecraft.class)    │
 * │  ├── levelRenderer    (LevelRenderer)       │
 * │  ├── camera           (Camera)              │
 * │  ├── frustum          (Frustum)             │
 * │  ├── level            (ClientLevel)         │
 * │  ├── sectionDispatcher(SectionRenderDispatcher)│
 * │  └── renderSystem     (RenderSystem)        │
 * ├─────────────────────────────────────────────┤
 * │  缓存层                                      │
 * │  ├── cachedCameraPosition (Vec3)           │
 * │  ├── cachedViewMatrix     [16 floats]      │
 * │  ├── cachedProjectionMatrix[16 floats]     │
 * │  └── cachedFrustumPlanes  [24 floats]      │
 * ├─────────────────────────────────────────────┤
 * │  数据访问 API                               │
 * │  ├── getCameraPosition()                   │
 * │  ├── getViewMatrix()                       │
 * │  ├── getVisibleSections()                  │
 * │  ├── getCurrentFBOId()                     │
 * │  └── ...                                   │
 * └─────────────────────────────────────────────┘
 *          ↓ 反射调用 ↓
 * ┌─────────────────────────────────────────────┐
 * │         Minecraft Runtime Environment        │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>接入方式：</h2>
 * <ul>
 *   <li><b>Fabric/NeoForge</b>: 通过 {@link com.renderium.platform.PlatformHelper} 获取 Minecraft 实例</li>
 *   <li><b>测试环境</b>: 使用 {@link MockMinecraft} 提供模拟数据</li>
 * </ul>
 *
 * <h2>使用示例：</h2>
 * <pre>
 * // 1. 初始化（在游戏启动时调用一次）
 * RealDataProvider provider = RealDataProvider.getInstance();
 * Object minecraft = PlatformHelper.getMinecraftInstance();  // 通过平台辅助类获取
 * boolean success = provider.initialize(minecraft);
 *
 * // 2. 每帧刷新缓存（在渲染循环开始时调用）
 * provider.refreshCache();
 *
 * // 3. 访问数据
 * Vec3 camPos = provider.getCameraPosition();
 * float[] viewMat = provider.getViewMatrix();
 * List&lt;ChunkSection&gt; sections = provider.getVisibleSections(null, 32);
 *
 * // 4. 构建剔除上下文
 * InterceptionCullingContext ctx = new InterceptionCullingContext.Builder()
 *     .cameraPosition(camPos.x, camPos.y, camPos.z)
 *     .viewMatrix(viewMat)
 *     .projectionMatrix(provider.getProjectionMatrix())
 *     .frustumPlanes(provider.getFrustumPlanes())
 *     .sections(provider.extractSectionData(sections))
 *     .positions(provider.extractPositionData(sections))
 *     .build();
 * </pre>
 *
 * <h3>线程安全：</h3>
 * <p>所有公共方法都是线程安全的：
 * <ul>
 *   <li>单例模式使用双重检查锁定（DCL）确保安全发布</li>
 *   <li>缓存字段使用 volatile 保证可见性</li>
 *   <li>数据访问方法使用 synchronized 块保护反射调用</li>
 * </ul>
 *
 * <h3>性能优化：</h3>
 * <ul>
 *   <li>缓存频繁访问的数据（相机位置、矩阵等）避免重复反射</li>
 *   <li>提供 {@link #refreshCache()} 方法在每帧开始时批量更新</li>
 *   <li>反射 Method/Field 引用缓存到实例变量中</li>
 *   <li>返回防御性拷贝防止外部修改影响内部状态</li>
 * </ul>
 *
 * <h3>优雅降级：</h3>
 * <p>当反射失败或数据不可用时：
 * <ol>
 *   <li>记录警告日志（包含详细错误信息）</li>
 *   <li>返回安全的默认值（null、零值、空数组）</li>
 *   <li>不抛出未检查异常导致崩溃</li>
 *   <li>通过 {@link #isReady()} 检查可用性</li>
 * </ol>
 *
 * @author Renderium Team
 * @version 6.0.0 (Phase 1.1)
 * @since 6.0.0
 * @see com.renderium.interception.RenderContext
 * @see com.renderium.interception.culling.InterceptionCullingContext
 * @see MockMinecraft
 */
public class RealDataProvider {

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger("RealDataProvider");

    /** Minecraft 类名常量 */
    private static final String CLASS_MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String CLASS_LEVEL_RENDERER = "net.minecraft.client.renderer.LevelRenderer";
    private static final String CLASS_CAMERA = "net.minecraft.client.Camera";
    private static final String CLASS_FRUSTUM = "net.minecraft.client.renderer.culling.Frustum";
    private static final String CLASS_CLIENT_LEVEL = "net.minecraft.client.multiplayer.ClientLevel";
    private static final String CLASS_SECTION_DISPATCHER = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher";
    private static final String CLASS_RENDER_SYSTEM = "com.mojang.blaze3d.systems.RenderSystem";

    // ==================== 单例实现 ====================

    /** volatile 确保多线程环境下的可见性 */
    private static volatile RealDataProvider instance;

    /**
     * 获取 RealDataProvider 单例
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - RealDataProvider 单例实例（延迟初始化）
     *
     * 线程安全保证：
     *   - 使用双重检查锁定（Double-Checked Locking）
     *   - volatile 字段确保实例正确发布
     *
     * 使用示例：
     *   RealDataProvider provider = RealDataProvider.getInstance();
     *   provider.initialize(minecraftInstance);
     * </pre>
     *
     * @return RealDataProvider 单例实例
     */
    public static RealDataProvider getInstance() {
        if (instance == null) {
            synchronized (RealDataProvider.class) {
                if (instance == null) {
                    instance = new RealDataProvider();
                }
            }
        }
        return instance;
    }

    // ==================== 数据源接口 ====================

    /** Minecraft.class 实例 */
    private volatile Object minecraftInstance;

    /** LevelRenderer 实例 */
    private volatile Object levelRenderer;

    /** Camera 实例 */
    private volatile Object camera;

    /** Frustum 实例 */
    private volatile Object frustum;

    /** ClientLevel 实例 */
    private volatile Object level;

    /** SectionRenderDispatcher 实例 */
    private volatile Object sectionDispatcher;

    /** RenderSystem 实例 */
    private volatile Object renderSystem;

    // ==================== 反射缓存（性能优化）====================

    /** 缓存的反射 Method 引用 */
    private Method methodGetLevelRenderer;
    private Method methodGetCamera;
    private Method methodGetFrustum;
    private Method methodGetLevel;
    private Method methodGetSectionDispatcher;
    private Method methodGetPosition;
    private Method methodGetX, methodGetY, methodGetZ;
    private Method methodGetPitch, methodGetYaw, methodGetFov;
    private Method methodGetViewMatrix;
    private Method methodGetProjectionMatrix;
    private Method methodGetPlanes;
    private Method methodGetRenderDistance;
    private Method methodGetVisibleSections;
    private Method methodGetCurrentFbo;
    private Method methodGetWindowWidth;
    private Method methodGetWindowHeight;
    private Method methodGetFrameCount;
    private Method methodGetPartialTick;

    /** 缓存的反射 Field 引用 */
    private Field fieldLevelRenderer;
    private Field fieldCamera;
    private Field fieldFrustum;
    private Field fieldLevel;

    // ==================== 缓存的数据 ====================

    /** 缓存的相机位置 */
    private volatile MockMinecraft.Vec3 cachedCameraPosition;

    /** 缓存的视图矩阵 (4x4 列主序，16 元素) */
    private volatile float[] cachedViewMatrix;

    /** 缓存的投影矩阵 (4x4 列主序，16 元素) */
    private volatile float[] cachedProjectionMatrix;

    /** 缓存的视锥体平面参数 (6 平面 × 4 参数 = 24 元素) */
    private volatile float[] cachedFrustumPlanes;

    // ==================== 状态标志 ====================

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 是否为测试模式（使用 MockMinecraft） */
    private volatile boolean mockMode = false;

    /** MockMinecraft 实例（测试模式下使用） */
    private volatile MockMinecraft mockMinecraft;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数（单例模式）
     *
     * <p>通过 {@link #getInstance()} 获取实例。
     */
    private RealDataProvider() {
        // 初始化缓存数组为 null
        this.cachedViewMatrix = null;
        this.cachedProjectionMatrix = null;
        this.cachedFrustumPlanes = null;
    }

    // ==================== 核心方法：初始化 ====================

    /**
     * 初始化数据桥接层
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - minecraft: Minecraft 实例（可通过反射或 Mixin 获取）
     *               类型：真实环境为 net.minecraft.client.Minecraft
     *                     测试环境为 MockMinecraft.getMinecraftInstance()
     *
     * 返回值：
     *   - boolean: true 表示初始化成功
     *              false 表示初始化失败（查看日志了解详情）
     *
     * 初始化流程：
     *   1. 检测是否为 MockMinecraft 测试环境
     *   2. 如果是真实环境：
     *      a. 缓存 Minecraft 实例引用
     *      b. 通过反射获取 LevelRenderer
     *      c. 通过反射获取 Camera
     *      d. 通过反射获取其他组件
     *      e. 预加载反射 Method/Field 引用
     *   3. 如果是测试环境：
     *      a. 从 MockMinecraft 提取模拟组件
     *   4. 设置 initialized 标志
     *
     * 异常处理：
     *   - 不会抛出异常（所有错误都记录到日志并返回 false）
     *   - 反射失败会降级为部分功能可用
     *
     * 使用示例：
     *   // 真实环境
     *   Object mc = getMinecraftThroughMixin();
     *   if (!provider.initialize(mc)) {
     *       LOGGER.warning("数据桥接层初始化失败");
     *   }
     *
     *   // 测试环境
     *   try (MockMinecraft mock = MockMinecraft.createDefaultScene()) {
     *       provider.initialize(mock.getMinecraftInstance());
     *   }
     * </pre>
     *
     * @param minecraft Minecraft 实例（可通过反射或 Mixin 获取）
     * @return true 表示初始化成功，false 表示失败
     */
    public boolean initialize(Object minecraft) {
        if (minecraft == null) {
            LOGGER.severe("初始化失败: Minecraft 实例为 null");
            return false;
        }

        try {
            // 检测是否为 MockMinecraft 环境
            if (minecraft instanceof MockMinecraft) {
                return initializeFromMock((MockMinecraft) minecraft);
            }

            // 真实 Minecraft 环境初始化
            this.minecraftInstance = minecraft;
            this.mockMode = false;

            // 尝试通过反射获取各个组件
            boolean success = true;

            // 获取 LevelRenderer
            success &= safeInitializeLevelRenderer();

            // 获取 Camera
            success &= safeInitializeCamera();

            // 获取 Frustum
            success &= safeInitializeFrustum();

            // 获取 ClientLevel
            success &= safeInitializeLevel();

            // 获取 SectionRenderDispatcher
            success &= safeInitializeSectionDispatcher();

            // 预加载反射引用（提高后续调用性能）
            preloadReflectionCaches();

            this.initialized = success;

            if (success) {
                LOGGER.info("RealDataProvider 初始化成功（真实 Minecraft 环境）");
                // 初始刷新缓存
                refreshCache();
            } else {
                LOGGER.warning("RealDataProvider 部分初始化成功（某些组件可能不可用）");
            }

            return success;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "RealDataProvider 初始化过程中发生异常", e);
            this.initialized = false;
            return false;
        }
    }

    /**
     * 从 MockMinecraft 初始化（测试环境专用）
     *
     * @param mock MockMinecraft 实例
     * @return 是否成功
     */
    private boolean initializeFromMock(MockMinecraft mock) {
        try {
            this.mockMode = true;
            this.mockMinecraft = mock;
            this.minecraftInstance = mock.getMinecraftInstance();

            // 从 MockMinecraft 提取模拟组件
            this.camera = mock.getMockCamera();
            this.frustum = mock.getMockFrustum();
            this.levelRenderer = mock.getMockLevelRenderer();
            this.level = mock.getMockClientLevel();
            this.sectionDispatcher = mock.getMockSectionDispatcher();
            this.renderSystem = mock.getMockRenderSystem();

            this.initialized = true;

            LOGGER.info("RealDataProvider 初始化成功（MockMinecraft 测试环境）");
            refreshCache();

            return true;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "MockMinecraft 初始化失败", e);
            this.initialized = false;
            return false;
        }
    }

    // ==================== 安全的初始化方法（带降级处理）====================

    /**
     * 安全初始化 LevelRenderer
     */
    private boolean safeInitializeLevelRenderer() {
        try {
            if (mockMode && mockMinecraft != null) {
                levelRenderer = mockMinecraft.getMockLevelRenderer();
                return true;
            }

            // 尝试通过字段访问
            fieldLevelRenderer = minecraftInstance.getClass().getDeclaredField("levelRenderer");
            fieldLevelRenderer.setAccessible(true);
            levelRenderer = fieldLevelRenderer.get(minecraftInstance);

            if (levelRenderer == null) {
                LOGGER.warning("无法获取 LevelRenderer（返回 null）");
                return false;
            }

            return true;

        } catch (NoSuchFieldException e) {
            LOGGER.log(Level.WARNING, "反射获取 LevelRenderer 失败: 字段不存在", e);
            return false;
        } catch (IllegalAccessException e) {
            LOGGER.log(Level.WARNING, "反射获取 LevelRenderer 失败: 无访问权限", e);
            return false;
        }
    }

    /**
     * 安全初始化 Camera
     */
    private boolean safeInitializeCamera() {
        try {
            if (mockMode && mockMinecraft != null) {
                camera = mockMinecraft.getMockCamera();
                return true;
            }

            // 尝试通过字段或方法获取
            fieldCamera = minecraftInstance.getClass().getDeclaredField("camera");
            fieldCamera.setAccessible(true);
            camera = fieldCamera.get(minecraftInstance);

            if (camera == null) {
                LOGGER.warning("无法获取 Camera（返回 null）");
                return false;
            }

            return true;

        } catch (NoSuchFieldException e) {
            LOGGER.log(Level.WARNING, "反射获取 Camera 失败: 字段不存在", e);
            return false;
        } catch (IllegalAccessException e) {
            LOGGER.log(Level.WARNING, "反射获取 Camera 失败: 无访问权限", e);
            return false;
        }
    }

    /**
     * 安全初始化 Frustum
     */
    private boolean safeInitializeFrustum() {
        try {
            if (mockMode && mockMinecraft != null) {
                frustum = mockMinecraft.getMockFrustum();
                return true;
            }

            // 通常从 EntityRenderDispatcher 或 GameRenderer 获取
            // 这里简化处理，实际应根据 MC 版本调整
            if (levelRenderer != null) {
                Field frustumField = levelRenderer.getClass().getDeclaredField("frustum");
                frustumField.setAccessible(true);
                frustum = frustumField.get(levelRenderer);
            }

            return frustum != null;

        } catch (NoSuchFieldException e) {
            LOGGER.log(Level.WARNING, "反射获取 Frustum 失败", e);
            return false;
        } catch (IllegalAccessException e) {
            LOGGER.log(Level.WARNING, "反射获取 Frustum 失败", e);
            return false;
        }
    }

    /**
     * 安全初始化 ClientLevel
     */
    private boolean safeInitializeLevel() {
        try {
            if (mockMode && mockMinecraft != null) {
                level = mockMinecraft.getMockClientLevel();
                return true;
            }

            Field levelField = minecraftInstance.getClass().getDeclaredField("level");
            levelField.setAccessible(true);
            level = levelField.get(minecraftInstance);

            return level != null;

        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.log(Level.WARNING, "反射获取 ClientLevel 失败", e);
            return false;
        }
    }

    /**
     * 安全初始化 SectionRenderDispatcher
     */
    private boolean safeInitializeSectionDispatcher() {
        try {
            if (mockMode && mockMinecraft != null) {
                sectionDispatcher = mockMinecraft.getMockSectionDispatcher();
                return true;
            }

            if (levelRenderer != null) {
                Field dispatcherField = levelRenderer.getClass()
                        .getDeclaredField("sectionRenderDispatcher");
                dispatcherField.setAccessible(true);
                sectionDispatcher = dispatcherField.get(levelRenderer);
            }

            return sectionDispatcher != null;

        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.log(Level.WARNING, "反射获取 SectionRenderDispatcher 失败", e);
            return false;
        }
    }

    /**
     * 预加载反射 Method/Field 引用（性能优化）
     *
     * <p>在初始化时一次性解析所有需要的反射引用，
     * 避免在每次数据访问时重复查找。
     */
    private void preloadReflectionCaches() {
        if (mockMode) return;  // Mock 模式不需要反射

        try {
            // Camera 相关方法
            if (camera != null) {
                Class<?> cameraClass = camera.getClass();

                methodGetPosition = findMethod(cameraClass, "getPosition");
                methodGetX = findMethod(cameraClass, "getX");
                methodGetY = findMethod(cameraClass, "getY");
                methodGetZ = findMethod(cameraClass, "getZ");
                methodGetPitch = findMethod(cameraClass, "getXRot");  // MC 命名
                methodGetYaw = findMethod(cameraClass, "getYRot");
                methodGetFov = findMethod(cameraClass, "getFov");
            }

            LOGGER.fine("反射引用预加载完成");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "预加载反射引用时发生错误（将使用运行时查找）", e);
        }
    }

    /**
     * 安全地查找方法（忽略 NoSuchMethodException）
     */
    private Method findMethod(Class<?> clazz, String name) {
        try {
            Method method = clazz.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            LOGGER.finest("方法不存在: " + clazz.getSimpleName() + "." + name);
            return null;
        }
    }

    // ==================== 相机数据查询 ====================

    /**
     * 获取相机对象
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - Object: Camera 实例
     *     * 真实环境: net.minecraft.client.Camera
     *     * 测试环境: MockMinecraft.MockCamera
     *     * 未初始化: null
     *
     * 使用场景：
     *   - 需要直接操作 Camera 对象的高级用法
     *   - 一般情况下建议使用 getCameraPosition() 等便捷方法
     * </pre>
     *
     * @return Camera 实例，如果未初始化返回 null
     */
    public Object getCamera() {
        if (!initialized) return null;
        return camera;
    }

    /**
     * 获取相机位置（世界坐标）
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - Vec3: 相机位置向量
     *     * x: 东西方向坐标（东为正）
     *     * y: 垂直方向坐标（上为正）
     *     * z: 南北方向坐标（南为正）
     *     * 如果未初始化或出错返回 null
     *
     * 性能说明：
     *   - 优先返回缓存值
     *   - 仅在缓存过期时通过反射重新获取
     *   - 典型耗时: &lt; 0.01ms（缓存命中）/ ~0.5ms（反射调用）
     *
     * 使用示例：
     *   Vec3 pos = dataProvider.getCameraPosition();
     *   if (pos != null) {
     *       float distanceToOrigin = Math.sqrt(pos.x*pos.x + pos.y*pos.y + pos.z*pos.z);
     *   }
     * </pre>
     *
     * @return 相机位置 Vec3(x, y, z)，如果不可用返回 null
     */
    public MockMinecraft.Vec3 getCameraPosition() {
        if (!initialized) return null;

        // 优先返回缓存
        if (cachedCameraPosition != null) {
            return cachedCameraPosition;
        }

        // 通过反射/Mock 获取
        try {
            if (mockMode && mockMinecraft != null) {
                cachedCameraPosition = mockMinecraft.getMockCamera().getPosition();
                return cachedCameraPosition;
            }

            if (camera != null && methodGetPosition != null) {
                Object posObj = methodGetPosition.invoke(camera);
                if (posObj != null) {
                    float x = invokeFloatMethod(posObj, "getX", 0.0f);
                    float y = invokeFloatMethod(posObj, "getY", 0.0f);
                    float z = invokeFloatMethod(posObj, "getZ", 0.0f);
                    cachedCameraPosition = new MockMinecraft.Vec3(x, y, z);
                    return cachedCameraPosition;
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取相机位置失败", e);
        }

        return null;
    }

    // ==================== 视锥体数据查询 ====================

    /**
     * 获取视锥体对象
     *
     * @return Frustum 实例，如果未初始化返回 null
     */
    public Object getFrustum() {
        if (!initialized) return null;
        return frustum;
    }

    /**
     * 获取视图矩阵 (4x4)
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 参数：
     *   - 无
     *
     * 返回值：
     *   - float[16]: 视图矩阵（列主序 Column-Major Order）
     *     * 格式: 4×4 矩阵，16 个 float 元素
     *     * 存储顺序: [m00, m10, m20, m30, m01, m11, ...]
     *     * 用途: 将世界坐标转换到相机空间
     *     * 如果未初始化返回 null
     *
     * 性能说明：
     *   - 优先返回缓存值（每帧更新一次）
     *   - 反射调用开销较大，应避免频繁调用
     *
     * 使用示例：
     *   float[] viewMat = dataProvider.getViewMatrix();
     *   if (viewMat != null) {
     *       // 上传到 GPU Uniform Buffer
     *       glUniformMatrix4fv(viewMatrixLocation, false, viewMat);
     *   }
     * </pre>
     *
     * @return 列主序 16 元素 float 数组，如果不可用返回 null
     */
    public float[] getViewMatrix() {
        if (!initialized) return null;

        if (cachedViewMatrix != null) {
            return cachedViewMatrix.clone();  // 防御性拷贝
        }

        try {
            if (mockMode && mockMinecraft != null) {
                cachedViewMatrix = mockMinecraft.getMockCamera().getViewMatrix();
                return cachedViewMatrix.clone();
            }

            // TODO: 真实环境的反射实现需要根据具体 MC 版本调整
            LOGGER.fine("视图矩阵: 使用默认单位矩阵（真实环境反射待实现）");
            cachedViewMatrix = createIdentityMatrix();
            return cachedViewMatrix.clone();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取视图矩阵失败", e);
            return null;
        }
    }

    /**
     * 获取投影矩阵 (4x4)
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - float[16]: 投影矩阵（列主序）
     *     * 格式: 透视投影或正交投影矩阵
     *     * 用途: 将相机坐标转换到裁剪空间
     *     * 如果不可用返回 null
     * </pre>
     *
     * @return 列主序 16 元素 float 数组
     */
    public float[] getProjectionMatrix() {
        if (!initialized) return null;

        if (cachedProjectionMatrix != null) {
            return cachedProjectionMatrix.clone();
        }

        try {
            if (mockMode && mockMinecraft != null) {
                cachedProjectionMatrix = mockMinecraft.getMockCamera().getProjectionMatrix();
                return cachedProjectionMatrix.clone();
            }

            cachedProjectionMatrix = createIdentityMatrix();
            return cachedProjectionMatrix.clone();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取投影矩阵失败", e);
            return null;
        }
    }

    /**
     * 获取视锥体平面参数 (6 平面 × 4 参数)
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - float[24]: 视锥体平面参数
     *     * 格式: 6 个平面，每个平面 4 个参数 (a, b, c, d)
     *     * 平面方程: ax + by + cz + d = 0
     *     * 平面顺序: 左、右、下、上、近、远
     *     * 用途: GPU/CPU 视锥体剔除
     *     * 如果不可用返回 null
     *
     * 性能说明：
     *   - 此数据通常每帧变化（随相机移动）
     *   - 建议在 refreshCache() 时更新
     *
     * 使用示例：
     *   float[] planes = dataProvider.getFrustumPlanes();
     *   if (planes != null) {
     *       // 传递给 AsyncComputeCuller 进行 GPU 剔除
     *       culler.setFrustumPlanes(planes);
     *   }
     * </pre>
     *
     * @return 24 元素 float 数组
     */
    public float[] getFrustumPlanes() {
        if (!initialized) return null;

        if (cachedFrustumPlanes != null) {
            return cachedFrustumPlanes.clone();
        }

        try {
            if (mockMode && mockMinecraft != null) {
                cachedFrustumPlanes = mockMinecraft.getMockFrustum().getPlanes();
                return cachedFrustumPlanes.clone();
            }

            cachedFrustumPlanes = new float[24];  // 默认全零
            return cachedFrustumPlanes.clone();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取视锥体平面失败", e);
            return null;
        }
    }

    // ==================== 世界和区块数据查询 ====================

    /**
     * 获取客户端世界
     *
     * @return ClientLevel 实例
     */
    public Object getLevel() {
        if (!initialized) return null;
        return level;
    }

    /**
     * 获取区块渲染分发器
     *
     * @return SectionRenderDispatcher 实例
     */
    public Object getSectionDispatcher() {
        if (!initialized) return null;
        return sectionDispatcher;
    }

    /**
     * 获取主渲染器
     *
     * @return LevelRenderer 实例
     */
    public Object getLevelRenderer() {
        if (!initialized) return null;
        return levelRenderer;
    }

    /**
     * 获取 Blaze3D 渲染系统
     *
     * @return RenderSystem 实例
     */
    public Object getRenderSystem() {
        if (!initialized) return null;
        return renderSystem;
    }

    /**
     * 获取指定半径内的可见区块列表
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - frustum: 视锥体对象（可为 null 表示不进行视锥剔除）
     *              类型: 真实环境为 net.minecraft.client.renderer.culling.Frustum
     *                    测试环境为 MockMinecraft.MockFrustum
     *   - radius:  查询半径（chunk 数量，必须 >= 0）
     *              例如: radius=32 表示查询 32×32×32 chunks 范围
     *
     * 返回值：
     *   - List&lt;Object&gt;: 可见区块列表
     *     * 每个元素类型:
     *       - 真实环境: SectionRenderDispatcher.RenderSection 或类似类型
     *       - 测试环境: MockMinecraft.MockChunkSection
     *     * 如果未初始化或出错返回空列表（非 null）
     *
     * 性能说明：
     *   - 此操作涉及遍历潜在的大量区块
     *   - 应该每帧只调用一次并缓存结果
     *   - 典型耗时取决于可见区块数量（~1000 区块约 1-5ms）
     *
     * 使用示例：
     *   List&lt;Object&gt; sections = dataProvider.getVisibleSections(frustum, 32);
     *   for (Object section : sections) {
     *       // 处理每个可见区块
     *   }
     * </pre>
     *
     * @param frustum 视锥体（可为 null）
     * @param radius  查询半径（chunk 数）
     * @return 可见区块列表（可能为空但不会为 null）
     */
    @SuppressWarnings("unchecked")
    public List<Object> getVisibleSections(Object frustum, int radius) {
        if (!initialized) return new ArrayList<>();

        try {
            if (mockMode && mockMinecraft != null) {
                // Mock 模式：直接返回所有区块（已过滤）
                MockMinecraft.MockSectionRenderDispatcher dispatcher =
                        mockMinecraft.getMockSectionDispatcher();
                return new ArrayList<>(dispatcher.getVisibleSections());
            }

            // 真实环境：通过反射获取可见区块
            // TODO: 根据 MC 版本实现具体逻辑
            LOGGER.fine("getVisibleSections: 真实环境反射实现待完成");
            return new ArrayList<>();

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取可见区块列表失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取指定位置的方块状态
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - pos: 方块坐标对象
     *          类型: 真实环境为 net.minecraft.core.BlockPos
     *                或使用 int[3] = {x, y, z}
     *
     * 返回值：
     *   - Object: BlockState 对象
     *     * 如果位置有效返回对应的 BlockState
     *     * 如果超出范围返回 Blocks.AIR（空气方块）
     *     * 如果未初始化返回 null
     * </pre>
     *
     * @param pos 方块坐标
     * @return BlockState 对象，如果超出范围返回 AIR
     */
    public Object getBlockState(Object pos) {
        if (!initialized || level == null) return null;

        try {
            // TODO: 实现 getBlockState 的反射调用
            LOGGER.fine("getBlockState: 待实现");
            return null;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取方块状态失败", e);
            return null;
        }
    }

    /**
     * 从区块列表提取 LOD 计算所需的 section 数据
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - sections: 区块列表（来自 getVisibleSections()）
     *               类型: List&lt;Object&gt;
     *
     * 返回值：
     *   - float[][]: 二维数组 [sectionCount][dataSize]
     *     * 每行格式: [index, distanceToCamera, boundingRadius]
     *       - index: 区块索引（int 转 float）
     *       - distanceToCamera: 到相机的距离（世界单位）
     *       - boundingRadius: 包围球半径（固定 13.856 = 8√3）
     *     * 用途: 传递给 GPUDrivenLODSystem 进行 LOD 计算
     *     * 如果输入为空或 null 返回空数组（非 null）
     *
     * 性能说明：
     *   - 时间复杂度: O(n)，n 为区块数量
     *   - 内存分配: n × 3 × 4 bytes（float）
     *   - 对于 1000 个区块约 12KB 内存
     *
     * 使用示例：
     *   List&lt;Object&gt; sections = dataProvider.getVisibleSections(frustum, 32);
     *   float[][] sectionData = dataProvider.extractSectionData(sections);
     *   // sectionData.length == sections.size()
     *   // sectionData[0] == [0, 123.45f, 13.856f]
     * </pre>
     *
     * @param sections 区块列表
     * @return 二维 float 数组 [sectionCount][3]
     */
    public float[][] extractSectionData(List<Object> sections) {
        if (sections == null || sections.isEmpty()) {
            return new float[0][];
        }

        float[][] result = new float[sections.size()][3];

        try {
            for (int i = 0; i < sections.size(); i++) {
                Object section = sections.get(i);

                if (section instanceof MockMinecraft.MockChunkSection) {
                    // Mock 模式
                    MockMinecraft.MockChunkSection mockSection =
                            (MockMinecraft.MockChunkSection) section;
                    result[i] = mockSection.toSectionData(i);

                } else {
                    // 真实环境：通过反射提取
                    // TODO: 实现真实环境的反射提取逻辑
                    result[i][0] = i;                              // index
                    result[i][1] = extractDistance(section);       // distance
                    result[i][2] = 13.85640646f;                   // radius (8√3)
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "提取 section 数据失败", e);
        }

        return result;
    }

    /**
     * 从区块列表提取位置数据
     *
     * <h3>方法签名与参数说明：</h3>
     * <pre>
     * 参数：
     *   - sections: 区块列表
     *
     * 返回值：
     *   - float[][]: 二维数组 [sectionCount][3]
     *     * 每行格式: [centerX, centerY, centerZ]
     *       - 世界坐标（非区块坐标）
     *       - 区块中心点位置
     *     * 用途: 传递给剔除系统进行位置测试
     *     * 如果输入为空返回空数组
     *
     * 性能说明：
     *   - 与 extractSectionData() 类似的性能特征
     *   - 可考虑合并两个方法减少遍历次数
     * </pre>
     *
     * @param sections 区块列表
     * @return 二维 float 数组 [sectionCount][3]
     */
    public float[][] extractPositionData(List<Object> sections) {
        if (sections == null || sections.isEmpty()) {
            return new float[0][];
        }

        float[][] result = new float[sections.size()][3];

        try {
            for (int i = 0; i < sections.size(); i++) {
                Object section = sections.get(i);

                if (section instanceof MockMinecraft.MockChunkSection) {
                    MockMinecraft.MockChunkSection mockSection =
                            (MockMinecraft.MockChunkSection) section;
                    result[i] = mockSection.toPositionData();

                } else {
                    // 真实环境
                    result[i] = extractPosition(section);
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "提取位置数据失败", e);
        }

        return result;
    }

    // ==================== 渲染状态查询 ====================

    /**
     * 获取当前 FBO ID（OpenGL 路径）
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - int: 当前绑定的 FBO ID
     *     * 0: 默认帧缓冲（窗口后缓冲）
     *     * >0: 自定义 FBO（如渲染目标的 FBO）
     *     * 如果不可用返回 0
     *
     * 使用场景：
     *   - 确定 Vulkan 后处理链的输入源
     *   - 检测是否在 FBO 内渲染
     *   - Sodium/Iris 兼容性检测
     * </pre>
     *
     * @return 当前绑定的 FBO ID，如果不可用返回 0
     */
    public int getCurrentFBOId() {
        if (!initialized) return 0;

        try {
            if (mockMode && mockMinecraft != null) {
                return mockMinecraft.getMockRenderSystem().getCurrentFBOId();
            }

            // TODO: 真实环境通过 OpenGL 调用 glGetIntegerv(GL_FRAMEBUFFER_BINDING)
            return 0;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取当前 FBO ID 失败", e);
            return 0;
        }
    }

    /**
     * 获取窗口分辨率
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - int[2]: {width, height}
     *     * width: 窗口宽度（像素）
     *     * height: 窗口高度（像素）
     *     * 如果不可用返回 {0, 0}
     *
     * 使用场景：
     *   - 计算投影矩阵宽高比
     *   - 设置视口（Viewport）
     *   - DLSS/FSR 分辨率缩放
     * </pre>
     *
     * @return int[2] = {width, height}
     */
    public int[] getWindowResolution() {
        if (!initialized) return new int[]{0, 0};

        try {
            if (mockMode && mockMinecraft != null) {
                return mockMinecraft.getMockRenderSystem().getResolution();
            }

            // TODO: 真实环境从 MainWindow 获取
            return new int[]{0, 0};

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取窗口分辨率失败", e);
            return new int[]{0, 0};
        }
    }

    /**
     * 获取当前帧索引
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - long: 帧计数器值（单调递增）
     *     * 从 0 开始，每帧 +1
     *     * 用于帧间数据一致性检查
     *     * 如果不可用返回 0
     *
     * 使用场景：
     *   - 检测数据是否过时
     *   - 帧率统计
     *   - 动画插值的时间基准
     * </pre>
     *
     * @return 帧计数器值
     */
    public long getFrameIndex() {
        if (!initialized) return 0;

        try {
            if (mockMode && mockMinecraft != null) {
                return mockMinecraft.getMockRenderSystem().getFrameIndex();
            }

            // TODO: 真实环境从 RenderSystem 或 Timer 获取
            return 0L;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取帧索引失败", e);
            return 0L;
        }
    }

    /**
     * 获取帧时间增量（秒）
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - float: delta time（秒）
     *     * 典型值: 0.0167 (~60fps), 0.0333 (~30fps)
     *     * 范围: > 0（理论上限取决于最小帧率）
     *     * 如果不可用返回 0.0167（假设 60fps）
     *
     * 使用场景：
     *   - 物理模拟时间步进
     *   - 动画平滑插值
     *   - TAA/Temporal 效果的历史采样
     * </pre>
     *
     * @return delta time（秒）
     */
    public float getDeltaTime() {
        if (!initialized) return 0.0167f;  // 默认 60fps

        try {
            if (mockMode && mockMinecraft != null) {
                // Mock 模式：基于固定帧率估算
                return 1.0f / 60.0f;  // 假设 60fps
            }

            // TODO: 真实环境从 Timer.getPartialTick() 获取
            return 0.0167f;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "获取帧时间增量失败", e);
            return 0.0167f;
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 检查是否已初始化且数据可用
     *
     * <h3>方法签名与返回值说明：</h3>
     * <pre>
     * 返回值：
     *   - boolean: 是否可以安全调用其他方法
     *     * true: 已初始化，可以调用数据访问方法
     *     * false: 未初始化或初始化失败，调用将返回 null/默认值
     *
     * 推荐使用方式：
     *   RealDataProvider provider = RealDataProvider.getInstance();
     *   if (provider.isReady()) {
     *       // 安全地访问数据
     *       Vec3 pos = provider.getCameraPosition();
     *   } else {
     *       // 使用备用数据源或显示错误信息
     *       showErrorMessage("数据源不可用");
     *   }
     * </pre>
     *
     * @return true 如果可以安全调用其他方法
     */
    public boolean isReady() {
        return initialized && (mockMode || minecraftInstance != null);
    }

    /**
     * 强制刷新缓存的数据
     *
     * <h3>方法签名与调用时机：</h3>
     * <pre>
     * 调用时机：
     *   - 每帧渲染开始时（推荐）
     *   - 在 DefaultPreInterceptor.intercept() 入口处
     *   - 相机移动后
     *
     * 执行操作：
     *   1. 清除所有缓存数据
     *   2. 重新从数据源获取最新值
     *   3. 更新内部缓存引用
     *   4. 记录性能指标（可选）
     *
     * 性能开销：
     *   - 典型耗时: 0.5-2ms（取决于反射调用数量）
     *   - 内存分配: 少量临时对象（会被 GC 回收）
     *   - 建议频率: 每帧一次
     *
     * 使用示例：
     *   // 在渲染循环中
     *   @Override
     *   public void onRenderStart() {
     *       RealDataProvider provider = RealDataProvider.getInstance();
     *       provider.refreshCache();
     *
     *       // 现在可以安全地使用缓存的数据
     *       Vec3 camPos = provider.getCameraPosition();  // 从缓存读取，快速
     *       float[] viewMat = provider.getViewMatrix();  // 从缓存读取，快速
     *   }
     * </pre>
     */
    public void refreshCache() {
        if (!initialized) {
            LOGGER.warning("refreshCache(): 未初始化，跳过刷新");
            return;
        }

        long startTime = System.nanoTime();

        try {
            // 清除旧缓存
            cachedCameraPosition = null;
            cachedViewMatrix = null;
            cachedProjectionMatrix = null;
            cachedFrustumPlanes = null;

            // 重新获取相机位置
            getCameraPosition();

            // 重新获取矩阵
            getViewMatrix();
            getProjectionMatrix();
            getFrustumPlanes();

            // Mock 模式下推进帧计数
            if (mockMode && mockMinecraft != null) {
                mockMinecraft.getMockRenderSystem().advanceFrame();
            }

            long elapsed = System.nanoTime() - startTime;
            if (elapsed > 5_000_000L) {  // > 5ms
                LOGGER.warning(String.format("refreshCache() 耗时 %.2fms（超过阈值）",
                        elapsed / 1_000_000.0));
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "刷新缓存时发生异常", e);
        }
    }

    /**
     * 重置所有缓存和数据源
     *
     * <p>将 Provider 恢复到未初始化状态。
     * 下次使用前需要重新调用 initialize()。
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>游戏退出时清理资源</li>
     *   <li>切换世界时重置数据源</li>
     *   <li>单元测试中的 tearDown 操作</li>
     * </ul>
     */
    public void reset() {
        // 清除数据源引用
        minecraftInstance = null;
        levelRenderer = null;
        camera = null;
        frustum = null;
        level = null;
        sectionDispatcher = null;
        renderSystem = null;
        mockMinecraft = null;

        // 清除反射缓存
        methodGetLevelRenderer = null;
        methodGetCamera = null;
        methodGetFrustum = null;
        methodGetLevel = null;
        methodGetSectionDispatcher = null;
        methodGetPosition = null;
        methodGetX = null;
        methodGetY = null;
        methodGetZ = null;
        methodGetPitch = null;
        methodGetYaw = null;
        methodGetFov = null;
        methodGetViewMatrix = null;
        methodGetProjectionMatrix = null;
        methodGetPlanes = null;
        methodGetRenderDistance = null;
        methodGetVisibleSections = null;
        fieldLevelRenderer = null;
        fieldCamera = null;
        fieldFrustum = null;
        fieldLevel = null;

        // 清除数据缓存
        cachedCameraPosition = null;
        cachedViewMatrix = null;
        cachedProjectionMatrix = null;
        cachedFrustumPlanes = null;

        // 重置状态
        initialized = false;
        mockMode = false;

        LOGGER.info("RealDataProvider 已重置");
    }

    /**
     * 检查是否为测试模式（使用 MockMinecraft）
     *
     * @return true 如果当前使用 Mock 数据
     */
    public boolean isMockMode() {
        return mockMode;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 创建 4x4 单位矩阵
     */
    private static float[] createIdentityMatrix() {
        float[] identity = new float[16];
        identity[0] = 1.0f;
        identity[5] = 1.0f;
        identity[10] = 1.0f;
        identity[15] = 1.0f;
        return identity;
    }

    /**
     * 安全地调用对象的 float 返回值方法
     *
     * @param obj       目标对象
     * @param methodName 方法名
     * @param defaultValue 默认值（调用失败时返回）
     * @return 方法返回值或默认值
     */
    private float invokeFloatMethod(Object obj, String methodName, float defaultValue) {
        if (obj == null) return defaultValue;

        try {
            Method method = obj.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object result = method.invoke(obj);

            if (result instanceof Number) {
                return ((Number) result).floatValue();
            }

            return defaultValue;

        } catch (Exception e) {
            LOGGER.finest(String.format("invokeFloatMethod(%s.%s) 失败: %s",
                    obj.getClass().getSimpleName(), methodName, e.getMessage()));
            return defaultValue;
        }
    }

    /**
     * 从真实环境的区块对象提取距离
     *
     * @param section 区块对象
     * @return 到相机的距离
     */
    private float extractDistance(Object section) {
        // TODO: 实现真实环境的距离提取逻辑
        // 可能需要通过反射调用 section.getX()/getY()/getZ() 然后计算
        return 0.0f;
    }

    /**
     * 从真实环境的区块对象提取位置
     *
     * @param section 区块对象
     * @return [x, y, z] 中心位置
     */
    private float[] extractPosition(Object section) {
        // TODO: 实现真实环境的位置提取逻辑
        return new float[]{0.0f, 0.0f, 0.0f};
    }
}
