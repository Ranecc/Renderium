// Renderium - Blaze3D 拦截层系统 Phase 4
// 超分辨率管理器 - 整合 DLSS/FSR/XeSS，支持自动降级

package com.ranecc.renderium.feature.intercept.post;
import com.ranecc.renderium.domain.model.config.RenderiumConfig;
import com.ranecc.renderium.None;


import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.intercept.post.sl.SLContext;
import com.ranecc.renderium.feature.intercept.post.vk.VulkanStreamlineBridge;
import com.ranecc.renderium.feature.intercept.post.sr.SuperResolutionContext;
import com.ranecc.renderium.feature.intercept.post.sr.SROutput;
/**
 * 超分辨率管理器（Super Resolution Manager）
 * <p>
 * 统一管理所有超分辨率技术的初始化、配置和执行。
 * 通过 Streamline SDK 提供对 DLSS、XeSS、FSR 的统一抽象层。
 *
 * <h3>核心技术：</h3>
 * <ul>
 *   <li><b>DLSS</b>（NVIDIA Deep Learning Super Sampling）- AI 驱动，质量最佳</li>
 *   <li><b>XeSS</b>（Intel Xe Super Sampling）- 基于 ML，Intel 平台优化</li>
 *   <li><b>FSR</b>（AMD FidelityFX Super Resolution）- 纯空间缩放，兼容性最好</li>
 * </ul>
 *
 * <h3>技术自动选择优先级：</h3>
 * <pre>
 * ┌─────────────────────────────────────────┐
 * │           技术选择决策树                 │
 * ├─────────────────────────────────────────┤
 * │                                         │
 * │  GPU = NVIDIA RTX ?                     │
 * │     ├── 是 → 检查 DLSS 可用性            │
 * │     │      ├── 可用 → 使用 DLSS ✓       │
 * │     │      └── 不可用 → 继续检查         │
 * │     └── 否 → 继续                       │
 * │                                         │
 * │  GPU = Intel Arc ?                      │
 * │     ├── 是 → 检查 XeSS 可用性            │
 * │     │      ├── 可用 → 使用 XeSS ✓       │
 * │     │      └── 不可用 → 继续检查         │
 * │     └── 否 → 继续                       │
 * │                                         │
 * │  回退到 FSR（纯软件，始终可用）          │
 * │  → 使用 FSR ✓                          │
 * │                                         │
 * └─────────────────────────────────────────┘
 * </pre>
 *
 * <h3>降级策略：</h3>
 * <p>当首选技术失败时自动降级：
 * <ol>
 *   <li>DLSS 失败 → 尝试 XeSS</li>
 *   <li>XeSS 失败 → 尝试 FSR</li>
 *   <li>FSR 失败 → 输出原始分辨率（无超分）</li>
 * </ol>
 *
 * <h3>性能预算：</h3>
 * <table border="1">
 *   <tr><th>阶段</th><th>兼容模式</th><th>狂暴模式</th></tr>
 *   <tr><td>超分辨率</td><td>&lt;8ms</td><td>&lt;6ms</td></tr>
 * </table>
 *
 * <h3>线程安全：</h3>
 * <p>此类完全线程安全。配置修改使用原子引用，
 * 执行方法可在渲染线程安全调用。
 *
 * @see SLContext Streamline SDK 上下文
 * @see SuperResolutionContext 超分辨率上下文
 * @since 5.2.0 (Phase 4)
 */
public final class SuperResolutionManager {

    private static final Logger LOGGER = Logger.getLogger(SuperResolutionManager.class.getName());

    // ==================== 单例实例 ====================

    /** 全局唯一实例（volatile 保证可见性和有序性） */
    private static volatile SuperResolutionManager INSTANCE;

    /**
     * 获取 SuperResolutionManager 单例实例
     * <p>
     * 使用双重检查锁定（DCL）保证线程安全的延迟初始化。
     *
     * @return SuperResolutionManager 全局唯一实例
     */
    public static SuperResolutionManager getInstance() {
        if (INSTANCE == null) {
            synchronized (SuperResolutionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SuperResolutionManager();
                }
            }
        }
        return INSTANCE;
    }

    // ==================== 枚举定义 ====================

    /**
     * 超分辨率技术类型枚举
     * <p>
     * 定义支持的所有超分辨率技术及其特性。
     */
    public enum SRTechnology {
        /** NVIDIA Deep Learning Super Sampling - 最佳质量，需 RTX GPU */
        DLSS("NVIDIA DLSS", "AI 驱动的超分辨率", true, false),

        /** Intel Xe Super Sampling - Intel Arc 优化 */
        XESS("Intel XeSS", "基于 ML 的超分辨率", true, false),

        /** AMD FidelityFX Super Resolution - 纯空间算法，最高兼容性 */
        FSR("AMD FSR", "开源空间超分辨率", false, true),

        /** 无超分辨率（降级/禁用状态） */
        NONE("None", "未启用超分辨率", false, true);

        private final String displayName;
        private final String description;
        private final boolean requiresHardwareSupport;  // 是否需要硬件加速
        private final boolean isSoftwareOnly;           // 是否为纯软件实现

        SRTechnology(String displayName, String description, 
                     boolean requiresHardwareSupport, boolean isSoftwareOnly) {
            this.displayName = displayName;
            this.description = description;
            this.requiresHardwareSupport = requiresHardwareSupport;
            this.isSoftwareOnly = isSoftwareOnly;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public boolean requiresHardwareSupport() { return requiresHardwareSupport; }
        public boolean isSoftwareOnly() { return isSoftwareOnly; }
    }

    /**
     * 质量模式枚举
     * <p>
     * 定义超分辨率的预设质量等级。
     * 不同技术可能映射到不同的内部参数。
     */
    public enum QualityMode {
        /** 质量优先模式 - 最高输出质量，渲染比例 ~67%-77% */
        QUALITY("Quality", 0.77f),

        /** 平衡模式 - 质量和性能的平衡点，渲染比例 ~67% */
        BALANCED("Balanced", 0.667f),

        /** 性能优先模式 - 最低渲染负载，渲染比例 ~50%-59% */
        PERFORMANCE("Performance", 0.59f);

        private final String name;
        private final float defaultRenderScale;  // 默认渲染分辨率比例

        QualityMode(String name, float defaultRenderScale) {
            this.name = name;
            this.defaultRenderScale = defaultRenderScale;
        }

        public String getName() { return name; }
        public float getDefaultRenderScale() { return defaultRenderScale; }
    }

    // ==================== 配置常量 ====================

    /** 渲染分辨率比例最小值 */
    public static final float MIN_RENDER_SCALE = 0.5f;

    /** 渲染分辨率比例最大值 */
    public static final float MAX_RENDER_SCALE = 1.0f;

    /** 锐化强度范围 [0.0, 1.0] */
    public static final float MIN_SHARPENING = 0.0f;
    public static final float MAX_SHARPENING = 1.0f;

    /** 性能预算：兼容模式（纳秒）= 8ms */
    private static final long COMPATIBILITY_BUDGET_NS = 8_000_000L;

    /** 性能预算：狂暴模式（纳秒）= 6ms */
    private static final long AGGRESSIVE_BUDGET_NS = 6_000_000L;

    // ==================== 核心状态字段 ====================

    /** 是否已初始化 */
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    /** 是否可用（至少有一种技术可用） */
    private final AtomicBoolean available = new AtomicBoolean(false);

    /** 当前激活的超分辨率技术 */
    private final AtomicReference<SRTechnology> activeTechnology = 
        new AtomicReference<>(SRTechnology.NONE);

    /** 当前可用的技术列表（按优先级排序） */
    private final List<SRTechnology> availableTechnologies = 
        Collections.synchronizedList(new ArrayList<>());

    // ==================== 配置字段（原子引用保证线程安全） ====================

    /** 当前质量模式 */
    private final AtomicReference<QualityMode> qualityMode = 
        new AtomicReference<>(QualityMode.BALANCED);

    /** 渲染分辨率比例 [0.5, 1.0] */
    private final AtomicReference<Float> renderScale = 
        new AtomicReference<>(0.667f);

    /** 锐化强度 [0.0, 1.0] */
    private final AtomicReference<Float> sharpeningAmount = 
        new AtomicReference<>(0.3f);

    // ==================== 外部依赖引用 ====================

    /** Streamline SDK 上下文 */
    private volatile SLContext slContext;

    /** Vulkan Bridge 引用 */
    private volatile VulkanStreamlineBridge vkBridge;

    /** Renderium 配置引用 */
    private volatile RenderiumConfig config;

    // ==================== 性能统计字段 ====================

    /** 上次处理耗时（纳秒） */
    private volatile long lastProcessTimeNanos = 0L;

    /** 上次使用的实际技术 */
    private volatile SRTechnology lastUsedTechnology = SRTechnology.NONE;

    /** 上次质量指标 */
    private volatile SRQualityMetrics lastQualityMetrics = null;

    /** 总处理次数 */
    private final AtomicInteger totalProcessCount = new AtomicInteger(0);

    /** 总成功次数 */
    private final AtomicInteger totalSuccessCount = new AtomicInteger(0);

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数 - 初始化默认配置
     */
    private SuperResolutionManager() {
        // 默认值已在字段声明中设置
    }

    // ==================== 生命周期方法 ====================

    /**
     * 初始化超分辨率管理器
     * <p>
     * 完整的初始化流程：
     * <ol>
     *   <li>验证 Streamline SDK 上下文有效性</li>
     *   <li>检测硬件支持的特性（DLSS/XeSS/FSR）</li>
     *   <li>应用 RenderiumConfig 配置</li>
     *   <li>确定最佳可用技术和回退链</li>
     * </ol>
     *
     * @param slContext Streamline SDK 上下文（不能为 null）
     * @param config   Renderium 配置（不能为 null）
     * @return true 表示初始化成功且至少有一种技术可用
     * @throws IllegalArgumentException 如果 slContext 或 config 为 null
     */
    public boolean initialize(SLContext slContext, RenderiumConfig config) {
        // ======== 参数校验 ========
        if (slContext == null) {
            throw new IllegalArgumentException("SLContext 不能为 null");
        }
        if (config == null) {
            throw new IllegalArgumentException("RenderiumConfig 不能为 null");
        }

        if (initialized.get()) {
            LOGGER.warning("SuperResolutionManager 已初始化，跳过重复初始化");
            return available.get();
        }

        try {
            // 保存依赖引用
            this.slContext = slContext;
            this.config = config;

            // 检测可用技术
            detectAvailableTechnologies();

            // 应用配置
            applyConfiguration(config);

            if (!availableTechnologies.isEmpty()) {
                // 设置默认活动技术（优先级最高的）
                selectBestAvailableTechnology();
                available.set(true);
                
                LOGGER.info(String.format(
                    "SuperResolutionManager 初始化完成 | 技术=%s | 质量=%s | 渲染比例=%.3f",
                    activeTechnology.get().getDisplayName(),
                    qualityMode.get().getName(),
                    renderScale.get()
                ));
                return true;
            } else {
                activeTechnology.set(SRTechnology.NONE);
                available.set(false);
                LOGGER.warning("无可用的超分辨率技术，将使用原始分辨率");
                return false;
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "SuperResolutionManager 初始化失败", e);
            available.set(false);
            return false;
        }
    }

    /**
     * 关闭超分辨率管理器并释放资源
     */
    public void shutdown() {
        if (!initialized.get()) {
            return;
        }

        try {
            // 清理状态
            initialized.set(false);
            available.set(false);
            activeTechnology.set(SRTechnology.NONE);
            
            synchronized (availableTechnologies) {
                availableTechnologies.clear();
            }

            // 清空引用
            slContext = null;
            vkBridge = null;
            config = null;

            // 重置统计
            totalProcessCount.set(0);
            totalSuccessCount.set(0);
            lastQualityMetrics = null;

            LOGGER.info("SuperResolutionManager 已关闭");

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "关闭时发生异常", e);
        }
    }

    // ==================== 核心执行方法 ====================

    /**
     * 执行超分辨率处理（核心方法）
     * <p>
     * 这是超分辨率的主入口点，在渲染线程中调用。
     * 根据当前配置的技术和质量设置，将低分辨率输入放大到高分辨率输出。
     *
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>校验输入参数和状态</li>
     *   <li>使用当前激活的技术执行超分</li>
     *   <li>如果失败则尝试降级到下一个可用技术</li>
     *   <li>收集性能指标和质量指标</li>
     *   <li>返回处理结果</li>
     * </ol>
     *
     * <h3>时间复杂度：</h3>O(n) 其中 n 为像素数量（与分辨率相关）
     *
     * @param context 超分辨率处理上下文（包含输入/输出纹理、分辨率、质量预设等）
     * @return 超分辨率处理结果（包含输出纹理句柄、耗时、使用的技术等）
     * @throws IllegalStateException 如果未初始化或无可用技术
     * @throws IllegalArgumentException 如果 context 为 null
     */
    public SROutput upscale(SuperResolutionContext context) {
        // ======== 参数校验 ========
        if (context == null) {
            throw new IllegalArgumentException("SuperResolutionContext 不能为 null");
        }

        // ======== 状态检查 ========
        if (!initialized.get() || !available.get()) {
            LOGGER.fine("SuperResolutionManager 未初始化或不可用，跳过超分");
            return new SROutput(false, 0, 0, 0, 0.0, SRTechnology.NONE);
        }

        long startTime = System.nanoTime();
        totalProcessCount.incrementAndGet();

        try {
            // ======== 尝试使用当前激活的技术 ========
            SRTechnology currentTech = activeTechnology.get();
            SROutput result = executeWithTechnology(context, currentTech);

            if (result.success()) {
                // 成功：更新统计信息
                updateSuccessStats(startTime, result, currentTech);
                return result;
            }

            // ======== 失败：尝试降级到下一个技术 ========
            LOGGER.warning(String.format(
                "%s 处理失败，开始降级...", currentTech.getDisplayName()
            ));

            SROutput fallbackResult = tryFallbackTechnologies(context, currentTech);
            
            if (fallbackResult.success()) {
                updateSuccessStats(startTime, fallbackResult, fallbackResult.usedTechnology());
                return fallbackResult;
            }

            // 所有技术都失败
            LOGGER.severe("所有超分辨率技术均失败，返回原始分辨率");
            long elapsedNs = System.nanoTime() - startTime;
            lastProcessTimeNanos = elapsedNs;
            lastUsedTechnology = SRTechnology.NONE;

            return new SROutput(false, 0, 
                context.getOutputWidth(), context.getOutputHeight(),
                elapsedNs / 1_000_000.0, SRTechnology.NONE);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "超分辨率处理异常", e);
            long elapsedNs = System.nanoTime() - startTime;
            lastProcessTimeNanos = elapsedNs;
            
            return new SROutput(false, 0, 0, 0, elapsedNs / 1_000_000.0, SRTechnology.NONE);
        }
    }

    // ==================== 技术查询方法 ====================

    /**
     * 获取当前激活的超分辨率技术
     *
     * @return 当前正在使用的技术枚举
     */
    public SRTechnology getActiveTechnology() {
        return activeTechnology.get();
    }

    /**
     * 获取所有可用的技术列表
     * <p>
     * 返回列表已按优先级排序（DLSS > XeSS > FSR）。
     *
     * @return 可用技术列表的副本（不可变视图）
     */
    public List<SRTechnology> getAvailableTechnologies() {
        synchronized (availableTechnologies) {
            return List.copyOf(availableTechnologies);
        }
    }

    /**
     * 检查是否有任何超分辨率技术可用
     *
     * @return true 如果至少有一种技术可用
     */
    public boolean isAvailable() {
        return available.get();
    }

    // ==================== 配置方法 ====================

    /**
     * 设置质量模式
     * <p>
     * 切换不同的质量预设，影响渲染分辨率比例和处理质量。
     *
     * @param mode 质量模式（QUALITY/BALANCED/PERFORMANCE）
     * @throws IllegalArgumentException 如果 mode 为 null
     */
    public void setQualityMode(QualityMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("QualityMode 不能为 null");
        }
        
        qualityMode.set(mode);
        renderScale.set(mode.getDefaultRenderScale());
        
        LOGGER.info(String.format(
            "超分辨率质量模式: %s (渲染比例=%.3f)",
            mode.getName(),
            mode.getDefaultRenderScale()
        ));
    }

    /**
     * 设置渲染分辨率比例
     * <p>
     * 控制内部渲染分辨率相对于显示分辨率的比例。
     * 较低的值意味着更高的性能但更低的质量。
     *
     * @param scale 渲染比例 [0.5, 1.0]
     * @throws IllegalArgumentException 如果 scale 超出有效范围
     */
    public void setRenderScale(float scale) {
        if (scale < MIN_RENDER_SCALE || scale > MAX_RENDER_SCALE) {
            throw new IllegalArgumentException(
                String.format("渲染比例必须在 [%.1f, %.1f] 范围内: %.3f",
                    MIN_RENDER_SCALE, MAX_RENDER_SCALE, scale)
            );
        }
        
        renderScale.set(scale);
        LOGGER.fine(String.format("渲染分辨率比例: %.3f", scale));
    }

    /**
     * 设置锐化强度
     * <p>
     * 控制超分辨率输出的锐化程度。
     * 较高的值可以增强细节，但可能导致伪影。
     *
     * @param amount 锐化强度 [0.0, 1.0]
     * @throws IllegalArgumentException 如果 amount 超出有效范围
     */
    public void setSharpening(float amount) {
        if (amount < MIN_SHARPENING || amount > MAX_SHARPENING) {
            throw new IllegalArgumentException(
                String.format("锐化强度必须在 [%.1f, %.1f] 范围内: %.2f",
                    MIN_SHARPENING, MAX_SHARPENING, amount)
            );
        }
        
        sharpeningAmount.set(amount);
        LOGGER.fine(String.format("锐化强度: %.2f", amount));
    }

    // ==================== 性能和质量指标方法 ====================

    /**
     * 获取上次的处理质量指标
     * <p>
     * 包含 SSIM 分数、感知质量评分、伪影计数等。
     *
     * @return 质量指标快照，如果没有处理过则返回 null
     */
    public SRQualityMetrics getLastQualityMetrics() {
        return lastQualityMetrics;
    }

    /**
     * 获取上次处理的耗时（毫秒）
     *
     * @return 耗时（ms），0 表示尚未处理
     */
    public double getLastProcessTimeMillis() {
        return lastProcessTimeNanos / 1_000_000.0;
    }

    /**
     * 获取成功率统计
     *
     * @return 成功率（0.0 - 1.0），如果尚未处理则返回 0.0
     */
    public double getSuccessRate() {
        int processCount = totalProcessCount.get();
        if (processCount == 0) {
            return 0.0;
        }
        return (double) totalSuccessCount.get() / processCount;
    }

    // ==================== 内部实现方法 ====================

    /**
     * 检测可用的超分辨率技术
     * <p>
     * 通过 Streamline SDK 查询各技术的硬件支持情况。
     * 结果存入 availableTechnologies 列表（按优先级排序）。
     */
    private void detectAvailableTechnologies() {
        if (slContext == null) {
            LOGGER.warning("SLContext 未设置，无法检测技术");
            return;
        }

        synchronized (availableTechnologies) {
            availableTechnologies.clear();

            // 检测 DLSS 支持
            if (slContext.isFeatureSupported(SLContext.Feature.DLSS)) {
                availableTechnologies.add(SRTechnology.DLSS);
                LOGGER.info("✓ DLSS 可用");
            } else {
                LOGGER.info("✗ DLSS 不可用");
            }

            // 检测 XeSS 支持（通过 Streamline 的 XeSS 特性检测）
            // 注意：XeSS 在 Streamline 中可能没有独立的 Feature 枚举
            // 这里做模拟检测
            if (isXeSSAvailable()) {
                availableTechnologies.add(SRTechnology.XESS);
                LOGGER.info("✓ XeSS 可用");
            } else {
                LOGGER.info("✗ XeSS 不可用");
            }

            // FSR 始终可用（软件实现）
            availableTechnologies.add(SRTechnology.FSR);
            LOGGER.info("✓ FSR 可用（软件实现）");

            LOGGER.info(String.format(
                "共检测到 %d 种可用超分辨率技术",
                availableTechnologies.size()
            ));
        }
    }

    /**
     * 检查 XeSS 是否可用
     * <p>
     * TODO: 完善 XeSS 可用性检测逻辑
     * 当前版本返回 false，待集成实际的 XeSS 检测代码后更新。
     *
     * @return true 如果 XeSS 可用
     */
    private boolean isXeSSAvailable() {
        // TODO: 实现 XeSS 可用性检测逻辑
        // 需要实现以下检测步骤：
        // 1. 检查 sl.xess.dll / libxess.so 是否存在于 Streamline 目录
        // 2. 查询 Streamline Feature 列表中是否包含 XeSS 特性
        // 3. 检查当前 GPU 是否为 Intel Arc / 支持 DP4a 指令集
        // 4. 验证 XeSS 模型文件是否完整可用
        
        return false;
    }

    /**
     * 应用 RenderiumConfig 配置
     * <p>
     * 从配置对象读取用户设置的质量模式、渲染比例、锐化等参数。
     *
     * @param config Renderium 配置实例
     */
    private void applyConfiguration(RenderiumConfig config) {
        try {
            // 应用超分辨率配置
            // 注意：这里从 RenderiumConfig 读取 postInterceptor.superResolution 相关配置
            
            // 获取首选技术（如果有明确指定）
            String preferredTech = config.getProperty("superResolution.preferredTechnology", "auto");
            
            // 获取质量模式
            String qualityStr = config.getProperty("superResolution.qualityMode", "balanced");
            switch (qualityStr.toLowerCase()) {
                case "quality" -> setQualityMode(QualityMode.QUALITY);
                case "performance" -> setQualityMode(QualityMode.PERFORMANCE);
                default -> setQualityMode(QualityMode.BALANCED);
            }

            // 获取渲染比例
            String scaleStr = config.getProperty("superResolution.renderScale", "0.667");
            try {
                float scale = Float.parseFloat(scaleStr);
                setRenderScale(scale);
            } catch (NumberFormatException e) {
                LOGGER.warning("无效的渲染比例配置: " + scaleStr + "，使用默认值");
            }

            // 获取锐化强度
            String sharpStr = config.getProperty("superResolution.sharpening", "0.3");
            try {
                float sharp = Float.parseFloat(sharpStr);
                setSharpening(sharp);
            } catch (NumberFormatException e) {
                LOGGER.warning("无效的锐化强度配置: " + sharpStr + "，使用默认值");
            }

            LOGGER.fine("RenderiumConfig 配置已应用到 SuperResolutionManager");

        } catch (Exception e) {
            LOGGER.warning("应用配置时出错，使用默认值: " + e.getMessage());
        }
    }

    /**
     * 选择最佳的可用技术
     * <p>
     * 从可用技术列表中选择优先级最高的作为当前激活技术。
     */
    private void selectBestAvailableTechnology() {
        synchronized (availableTechnologies) {
            if (!availableTechnologies.isEmpty()) {
                SRTechnology best = availableTechnologies.get(0);  // 已按优先级排序
                activeTechnology.set(best);
                LOGGER.info("选择超分辨率技术: " + best.getDisplayName());
            }
        }
    }

    /**
     * 使用指定的技术执行超分辨率处理
     * <p>
     * 内部核心方法，根据技术类型调用不同的实现路径。
     *
     * @param context 处理上下文
     * @param tech    要使用的技术
     * @return 处理结果
     */
    private SROutput executeWithTechnology(SuperResolutionContext context, SRTechnology tech) {
        long startTime = System.nanoTime();

        try {
            // TODO: Phase 5 集成实际的超分辨率调用
            // 各技术的具体实现：
            //
            // DLSS:
            //   slDLSSEvaluate(sl::ViewportHandle, const sl::DLSSParams& params)
            //
            // XeSS:
            //   slXSSEvaluate(sl::ViewportHandle, const sl::XSSParams& params)
            //
            // FSR:
            //   slFSREvaluate(sl::ViewportHandle, const sl::FSRParams& params)

            // 模拟处理过程（记录日志）
            LOGGER.fine(String.format(
                "执行超分辨率: %s | 输入=%dx%d -> 输出=%dx%d | 质量=%s",
                tech.getDisplayName(),
                context.getInputWidth(), context.getInputHeight(),
                context.getOutputWidth(), context.getOutputHeight(),
                qualityMode.get().getName()
            ));

            // 模拟成功结果
            long elapsedNs = System.nanoTime() - startTime;
            
            // 返回成功的输出（outputTextureHandle 暂时使用输入纹理句柄作为占位符）
            return new SROutput(
                true,
                context.getInputColorTexture(),  // TODO: 应为实际输出纹理句柄
                context.getOutputWidth(),
                context.getOutputHeight(),
                elapsedNs / 1_000_000.0,
                tech
            );

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, 
                tech.getDisplayName() + " 执行失败: " + e.getMessage(), e);
            
            long elapsedNs = System.nanoTime() - startTime;
            return new SROutput(false, 0, 0, 0, elapsedNs / 1_000_000.0, tech);
        }
    }

    /**
     * 尝试使用备用技术进行降级处理
     * <p>
     * 当首选技术失败时，按照优先级顺序尝试其他可用技术。
     * 降级链：DLSS → XeSS → FSR
     *
     * @param context      处理上下文
     * @param failedTech   已失败的技术（将从候选列表中排除）
     * @return 降级处理结果，如果所有技术都失败则返回不成功的结果
     */
    private SROutput tryFallbackTechnologies(SuperResolutionContext context, SRTechnology failedTech) {
        List<SRTechnology> candidates;
        
        synchronized (availableTechnologies) {
            candidates = new ArrayList<>(availableTechnologies);
        }

        for (SRTechnology candidate : candidates) {
            // 跳过已失败的技术
            if (candidate == failedTech) {
                continue;
            }

            LOGGER.info(String.format("尝试降级到: %s", candidate.getDisplayName()));
            
            SROutput result = executeWithTechnology(context, candidate);
            
            if (result.success()) {
                // 降级成功，更新活动技术
                activeTechnology.set(candidate);
                LOGGER.warning(String.format(
                    "降级成功: %s -> %s",
                    failedTech.getDisplayName(),
                    candidate.getDisplayName()
                ));
                return result;
            }

            LOGGER.warning(candidate.getDisplayName() + " 也失败了");
        }

        // 所有降级都失败
        return new SROutput(false, 0, 0, 0, 0.0, SRTechnology.NONE);
    }

    /**
     * 更新成功处理的统计数据
     *
     * @param startTime 开始时间戳（纳秒）
     * @param result    处理结果
     * @param tech      使用的实际技术
     */
    private void updateSuccessStats(long startTime, SROutput result, SRTechnology tech) {
        long elapsedNs = System.nanoTime() - startTime;
        
        // 更新公共状态
        lastProcessTimeNanos = elapsedNs;
        lastUsedTechnology = tech;
        totalSuccessCount.incrementAndGet();

        // 生成质量指标（模拟数据，实际应从 SDK 获取）
        lastQualityMetrics = new SRQualityMetrics(
            calculateSimulatedSSIM(result),      // SSIM 分数
            calculatePerceptualQuality(result),   // 感知质量
            estimateArtifactCount(result)         // 伪影估计
        );

        // 性能预算检查
        long budget = (config != null && config.getMode().name().contains("AGGRESSIVE")) 
            ? AGGRESSIVE_BUDGET_NS : COMPATIBILITY_BUDGET_NS;
        
        if (elapsedNs > budget) {
            LOGGER.warning(String.format(
                "超分辨率超出性能预算: %.2f ms > %.2f ms (技术=%s)",
                elapsedNs / 1_000_000.0,
                budget / 1_000_000.0,
                tech.getDisplayName()
            ));
        }
    }

    // ==================== 质量指标计算（模拟） ====================

    /**
     * 计算模拟的 SSIM 分数
     * <p>
     * TODO: 替换为实际的 SSIM 计算或从 SDK 获取真实值
     * 当前版本根据处理时间和技术类型生成合理的估算值。
     *
     * @param result 处理结果
     * @return SSIM 分数 [0.95, 0.99]
     */
    private float calculateSimulatedSSIM(SROutput result) {
        // 基于技术类型的基础分数
        float baseScore = switch (result.usedTechnology()) {
            case DLSS -> 0.98f;   // DLSS 质量最佳
            case XESS -> 0.97f;   // XeSS 次之
            case FSR -> 0.96f;    // FSR 第三
            case NONE -> 1.0f;    // 无超分为原始图像
        };

        // 根据处理时间微调（越快通常质量略低）
        float timeFactor = (float) Math.max(0.5, Math.min(1.0, 10.0 / (result.processTimeMs() + 1.0)));
        
        return Math.min(0.99f, baseScore * timeFactor + 0.01f);
    }

    /**
     * 计算感知质量评分
     * <p>
     * 综合考虑锐度、噪声、伪影等因素的感知质量评估。
     *
     * @param result 处理结果
     * @return 感知质量评分 [0.9, 1.0]
     */
    private float calculatePerceptualQuality(SROutput result) {
        // 基于技术类型的基准质量
        float baseQuality = switch (result.usedTechnology()) {
            case DLSS -> 0.97f;
            case XESS -> 0.95f;
            case FSR -> 0.93f;
            case NONE -> 1.0f;
        };

        // 锐化因子影响
        float sharpFactor = 1.0f - (sharpeningAmount.get() * 0.05f);
        
        return Math.min(1.0f, baseQuality * sharpFactor + 0.02f);
    }

    /**
     * 估计伪影数量
     * <p>
     * 基于处理参数估算可能的伪影数量。
     * 实际应使用专门的伪影检测算法。
     *
     * @param result 处理结果
     * @return 估计的伪影数量
     */
    private int estimateArtifactCount(SROutput result) {
        // 低渲染比例可能导致更多伪影
        float scale = renderScale.get();
        int baseArtifacts = (int) ((1.0f - scale) * 20);  // 基础伪影

        // 高锐化增加伪影风险
        int sharpArtifacts = (int) (sharpeningAmount.get() * 5);

        // 技术特定修正
        int techBonus = switch (result.usedTechnology()) {
            case DLSS -> -3;   // DLSS 伪影较少
            case XESS -> -1;
            case FSR -> 2;     // FSR 可能稍多
            case NONE -> -10;  // 无超分无额外伪影
        };

        return Math.max(0, baseArtifacts + sharpArtifacts + techBonus);
    }

    // ==================== 内部类定义 ====================

    /**
     * 超分辨率输出结果（不可变 Record）
     * <p>
     * 封装一次超分辨率处理的完整结果。
     *
     * @param success           是否成功
     * @param outputTextureHandle 输出纹理句柄（Vulkan ImageView）
     * @param outputWidth       输出宽度（像素）
     * @param outputHeight      输出高度（像素）
     * @param processTimeMs     处理耗时（毫秒）
     * @param usedTechnology    实际使用的技术（可能与请求的不同，如降级后）
     */
    public record SROutput(
        boolean success,
        long outputTextureHandle,
        int outputWidth,
        int outputHeight,
        double processTimeMs,
        SRTechnology usedTechnology
    ) {}

    /**
     * 超分辨率质量指标（不可变 Record）
     * <p>
     * 用于评估超分辨率输出的视觉质量。
     *
     * @param ssimScore        结构相似性指数 [0, 1]，越高越好
     * @param perceptualQuality 感知质量评分 [0, 1]
     * @param artifactCount    检测到的伪影数量，越少越好
     */
    public record SRQualityMetrics(
        float ssimScore,
        float perceptualQuality,
        int artifactCount
    ) {}
}
