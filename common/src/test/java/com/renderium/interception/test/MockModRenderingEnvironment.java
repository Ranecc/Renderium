// Renderium - 模组渲染环境模拟测试工具
// 双模式验收测试套件 - 模拟第三方渲染模组环境，用于测试兼容模式

package com.renderium.interception.test;

import com.renderium.graphics.backend.RenderBackendProxy;
import com.renderium.graphics.event.VulkanActivationEvent;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 第三方渲染模组环境模拟器
 * <p>
 * 模拟第三方渲染模组（如性能优化模组）的渲染行为，用于在无实际 Minecraft/模组环境下
 * 执行兼容模式的验收测试。
 *
 * <h3>核心功能：</h3>
 * <ul>
 *   <li><b>Mock FBO 管理</b>：模拟 FBO 绑定/解绑操作</li>
 *   <li><b>Mock 渲染目标</b>：模拟渲染目标的创建和切换</li>
 *   <li><b>可配置版本号</b>：支持模拟不同版本的第三方模组</li>
 *   <li><b>可配置后端</b>：支持 OpenGL/Vulkan 后端切换</li>
 *   <li><b>性能模拟</b>：可配置的模拟延迟和帧时间</li>
 * </ul>
 *
 * <h3>架构设计：</h3>
 * <pre>
 * +-------------------------------------+
 * |      MockModRenderingEnvironment    |
 * |  +-----------------------------+    |
 * |  | MockFBOManager              |    |
 * |  | - bindFBO() / unbindFBO()  |    |
 * |  | - getActiveFBO()           |    |
 * |  +-----------------------------+    |
 * |  +-----------------------------+    |
 * |  | MockRenderTarget            |    |
 * |  | - createTarget()            |    |
 * |  | - switchTarget()            |    |
 * |  +-----------------------------+    |
 * |  +-----------------------------+    |
 * |  | PerformanceSimulator        |    |
 * |  | - simulateFrameTime()       |    |
 * |  | - setLatencyModel()         |    |
 * |  +-----------------------------+    |
 * +-------------------------------------+
 * </pre>
 *
 * @author Renderium Team
 * @since 5.6.0 (Phase 6)
 */
public final class MockModRenderingEnvironment {

    // ==================== 常量定义 ====================

    /** 日志记录器 */
    private static final Logger LOGGER = Logger.getLogger(MockModRenderingEnvironment.class.getName());

    /** 默认 Mock FBO ID 起始值 */
    private static final int MOCK_FBO_BASE_ID = 0x1000;

    /** 默认 Mock 纹理 ID 起始值 */
    private static final int MOCK_TEXTURE_BASE_ID = 0x2000;

    /** 默认模拟分辨率宽度 */
    public static final int DEFAULT_WIDTH = 1920;

    /** 默认模拟分辨率高度 */
    public static final int DEFAULT_HEIGHT = 1080;

    // ==================== 实例状态 ====================

    /** 当前绑定的 FBO ID（0 表示未绑定） */
    private final AtomicInteger currentFBO = new AtomicInteger(0);

    /** FBO ID 分配计数器 */
    private final AtomicInteger fboIdCounter = new AtomicInteger(MOCK_FBO_BASE_ID);

    /** 纹理 ID 分配计数器 */
    private final AtomicInteger textureIdCounter = new AtomicInteger(MOCK_TEXTURE_BASE_ID);

    /** 已创建的 FBO 映射表 (fboId -> FBOInfo) */
    private final Map<Integer, MockFBOInfo> fboRegistry = new ConcurrentHashMap<>();

    /** 已创建的纹理映射表 (textureId -> TextureInfo) */
    private final Map<Integer, MockTextureInfo> textureRegistry = new ConcurrentHashMap<>();

    /** 渲染目标栈（支持嵌套目标） */
    private final Deque<MockRenderTarget> renderTargetStack = new ArrayDeque<>();

    /** 被模拟的模组版本信息 */
    private volatile String modVersion;

    /** 渲染后端类型 */
    private volatile BackendType backendType;

    /** 性能模拟器 */
    private final PerformanceSimulator performanceSimulator;

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    /** 帧计数器 */
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    // ==================== 枚举和内部类 ====================

    /**
     * 渲染后端类型枚举
     */
    public enum BackendType {
        /** OpenGL 后端（标准模式） */
        OPENGL,
        /** Vulkan 后端（实验性） */
        VULKAN,
        /** 自定义后端（用于测试） */
        CUSTOM
    }

    /**
     * Mock FBO 信息
     *
     * @param fboId        FBO ID
     * @param width        宽度
     * @param height       高度
     * @param colorTexture 颜色纹理 ID
     * @param depthTexture 深度纹理 ID（0 表示无）
     * @param samples      MSAA 采样数
     */
    public record MockFBOInfo(
            int fboId,
            int width,
            int height,
            int colorTexture,
            int depthTexture,
            int samples
    ) {
        /**
         * 检查是否有深度附件
         *
         * @return true 如果有深度纹理
         */
        public boolean hasDepthAttachment() {
            return depthTexture != 0;
        }
    }

    /**
     * Mock 纹理信息
     *
     * @param textureId 纹理 ID
     * @param width     宽度
     * @param height    高度
     * @param format    格式描述
     */
    public record MockTextureInfo(
            int textureId,
            int width,
            int height,
            String format
    ) {}

    /**
     * Mock 渲染目标
     *
     * @param name          目标名称
     * @param fboId         关联的 FBO ID
     * @param width         宽度
     * @param height        高度
     * @param clearOnBind   绑定时是否清除
     */
    public record MockRenderTarget(
            String name,
            int fboId,
            int width,
            int height,
            boolean clearOnBind
    ) {}

    // ==================== 构造函数 ====================

    /**
     * 创建默认配置的模组渲染环境模拟器
     * <p>
     * 使用默认参数：
     * <ul>
"1.0.0"
     *   <li>模组版本: "1.0.0"</li>
     *   <li>后端类型: OPENGL</li>
     *   <li>分辨率: 1920x1080</li>
     * </ul>
     */
    public MockModRenderingEnvironment() {
        this("1.0.0", BackendType.OPENGL, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * 创建自定义配置的模组渲染环境模拟器
     *
     * @param version     模组版本字符串
     * @param backend     渲染后端类型
     * @param width       默认渲染宽度
     * @param height      默认渲染高度
     */
    public MockModRenderingEnvironment(String version, BackendType backend, int width, int height) {
        this.modVersion = version;
        this.backendType = backend;
        this.performanceSimulator = new PerformanceSimulator(width, height);
        LOGGER.info(String.format("MockModRenderingEnvironment 创建: version=%s, backend=%s, %dx%d",
                version, backend, width, height));
    }

    // ==================== 初始化和生命周期 ====================

    /**
     * 初始化模组渲染环境模拟器
     * <p>
     * 创建默认渲染目标和必要的资源。
     *
     * @return true 如果初始化成功
     * @throws IllegalStateException 如果已经初始化
     */
    public synchronized boolean initialize() {
        if (initialized) {
            throw new IllegalStateException("MockModRenderingEnvironment 已经初始化");
        }

        try {
            // 创建主渲染目标（模拟模组的主 FBO）
            int mainFBO = createFBO(width(), height(), 1);  // 无 MSAA
            pushRenderTarget("main", mainFBO, width(), height(), true);

            initialized = true;
            LOGGER.info("MockModRenderingEnvironment 初始化完成");
            return true;

        } catch (Exception e) {
            LOGGER.severe("MockModRenderingEnvironment 初始化失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 关闭模组渲染环境模拟器
     * <p>
     * 释放所有创建的资源，重置状态。
     */
    public synchronized void shutdown() {
        if (!initialized) {
            LOGGER.warning("MockModRenderingEnvironment 尚未初始化");
            return;
        }

        // 清空所有注册表
        fboRegistry.clear();
        textureRegistry.clear();
        renderTargetStack.clear();

        // 重置状态
        currentFBO.set(0);
        frameCounter.set(0);
        initialized = false;

        LOGGER.info("MockModRenderingEnvironment 已关闭");
    }

    /**
     * 检查是否已初始化
     *
     * @return true 如果已初始化且未关闭
     */
    public boolean isInitialized() {
        return initialized;
    }

    // ==================== FBO 操作 ====================

    /**
     * 创建新的 Mock FBO
     *
     * @param width   FBO 宽度
     * @param height  FBO 高度
     * @param samples MSAA 采样数（1 表示无 MSAA）
     * @return 新分配的 FBO ID
     * @throws IllegalArgumentException 如果尺寸无效
     */
    public int createFBO(int width, int height, int samples) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("FBO 尺寸必须大于 0: " + width + "x" + height);
        }

        // 分配新 FBO ID
        int fboId = fboIdCounter.getAndIncrement();

        // 创建颜色纹理
        int colorTex = textureIdCounter.getAndIncrement();
        MockTextureInfo colorInfo = new MockTextureInfo(colorTex, width, height, "RGBA8");
        textureRegistry.put(colorTex, colorInfo);

        // 可选创建深度纹理
        int depthTex = 0;
        if (samples <= 4) {  // 仅在低采样率时创建深度
            depthTex = textureIdCounter.getAndIncrement();
            MockTextureInfo depthInfo = new MockTextureInfo(depthTex, width, height, "DEPTH24");
            textureRegistry.put(depthTex, depthInfo);
        }

        // 注册 FBO
        MockFBOInfo fboInfo = new MockFBOInfo(fboId, width, height, colorTex, depthTex, samples);
        fboRegistry.put(fboId, fboInfo);

        LOGGER.fine(String.format("创建 Mock FBO: id=0x%X, %dx%d, samples=%d",
                fboId, width, height, samples));

        return fboId;
    }

    /**
     * 绑定 FBO（模拟 glBindFramebuffer）
     *
     * @param fboId 要绑定的 FBO ID（0 表示解绑到默认）
     */
    public void bindFBO(int fboId) {
        if (fboId != 0 && !fboRegistry.containsKey(fboId)) {
            LOGGER.warning("尝试绑定不存在的 FBO: 0x" + Integer.toHexString(fboId));
        }

        int previousFBO = currentFBO.getAndSet(fboId);
        LOGGER.fine(String.format("绑定 FBO: 0x%X -> 0x%X", previousFBO, fboId));
    }

    /**
     * 解绑当前 FBO（绑定到默认帧缓冲区）
     */
    public void unbindFBO() {
        bindFBO(0);
    }

    /**
     * 获取当前绑定的 FBO ID
     *
     * @return 当前 FBO ID（0 表示默认）
     */
    public int getCurrentFBO() {
        return currentFBO.get();
    }

    /**
     * 获取 FBO 信息
     *
     * @param fboId FBO ID
     * @return FBO 信息，如果不存在返回 null
     */
    public MockFBOInfo getFBOInfo(int fboId) {
        return fboRegistry.get(fboId);
    }

    /**
     * 检查 FBO 是否存在
     *
     * @param fboId FBO ID
     * @return true 如果存在
     */
    public boolean hasFBO(int fboId) {
        return fboRegistry.containsKey(fboId);
    }

    // ==================== 渲染目标操作 ====================

    /**
     * 推入新的渲染目标（模拟目标切换）
     *
     * @param name        目标名称（用于调试）
     * @param fboId       目标 FBO ID
     * @param width       目标宽度
     * @param height      目标高度
     * @param clearOnBind 绑定时是否清除
     * @return 创建的 MockRenderTarget
     */
    public MockRenderTarget pushRenderTarget(String name, int fboId, int width,
                                              int height, boolean clearOnBind) {
        MockRenderTarget target = new MockRenderTarget(name, fboId, width, height, clearOnBind);
        renderTargetStack.push(target);

        if (clearOnBind) {
            clearFBO(fboId);
        }

        LOGGER.fine(String.format("推送渲染目标: '%s' -> [栈深度=%d]",
                name, renderTargetStack.size()));

        return target;
    }

    /**
     * 弹出当前渲染目标（恢复上一个目标）
     *
     * @return 弹出的 MockRenderTarget，如果栈为空返回 null
     */
    public MockRenderTarget popRenderTarget() {
        if (renderTargetStack.isEmpty()) {
            LOGGER.warning("渲染目标栈为空，无法弹出");
            return null;
        }

        MockRenderTarget target = renderTargetStack.pop();

        // 恢复到前一个目标的 FBO
        if (!renderTargetStack.isEmpty()) {
            MockRenderTarget prevTarget = renderTargetStack.peek();
            bindFBO(prevTarget.fboId());
        } else {
            unbindFBO();
        }

        LOGGER.fine(String.format("弹出渲染目标: '%s' -> [栈深度=%d]",
                target.name(), renderTargetStack.size()));

        return target;
    }

    /**
     * 获取当前渲染目标
     *
     * @return 当前 MockRenderTarget，如果栈为空返回 null
     */
    public MockRenderTarget getCurrentRenderTarget() {
        return renderTargetStack.peek();
    }

    /**
     * 获取渲染目标栈深度
     *
     * @return 栈深度
     */
    public int getRenderTargetStackSize() {
        return renderTargetStack.size();
    }

    // ==================== 模拟渲染操作 ====================

    /**
     * 清除指定 FBO 的内容
     *
     * @param fboId 目标 FBO ID
     */
    public void clearFBO(int fboId) {
        MockFBOInfo info = fboRegistry.get(fboId);
        if (info != null) {
            LOGGER.fine(String.format("清除 FBO: 0x%X (%dx%d)", fboId, info.width(), info.height()));
        }
    }

    /**
     * 模拟一帧渲染完成
     * <p>
     * 更新帧计数器并记录性能数据。
     *
     * @return 模拟的帧耗时（纳秒）
     */
    public long simulateFrameComplete() {
        int frameIndex = frameCounter.incrementAndGet();

        // 使用性能模拟器生成帧时间
        long frameTimeNs = performanceSimulator.simulateFrameTime(frameIndex);

        LOGGER.finest(String.format("模拟帧 #%d 完成: %.2fms",
                frameIndex, frameTimeNs / 1_000_000.0));

        return frameTimeNs;
    }

    /**
     * 模拟读取 FBO 内容（模拟 glReadPixels）
     *
     * @param fboId  目标 FBO ID
     * @param width  读取宽度
     * @param height 读取高度
     * @return 模拟的像素数据缓冲区
     */
    public ByteBuffer mockReadPixels(int fboId, int width, int height) {
        MockFBOInfo info = fboRegistry.get(fboId);
        if (info == null) {
            LOGGER.warning("尝试从不存在的 FBO 读取像素: 0x" + Integer.toHexString(fboId));
            return ByteBuffer.allocateDirect(0);
        }

        // 计算所需缓冲区大小（RGBA 格式，每像素 4 字节）
        int bufferSize = width * height * 4;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);

        // 填充模拟数据（渐变图案，便于验证）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                byte r = (byte) ((x * 255) / width);
                byte g = (byte) ((y * 255) / height);
                byte b = (byte) 128;
                byte a = (byte) 255;
                buffer.put(r).put(g).put(b).put(a);
            }
        }
        buffer.flip();

        LOGGER.fine(String.format("模拟读取像素: fbo=0x%X, %dx%d, %d bytes",
                fboId, width, height, bufferSize));

        return buffer;
    }

    // ==================== 配置查询方法 ====================

    /**
     * 获取模拟的模组版本
     *
     * @return 版本字符串
     */
    public String getModVersion() {
        return modVersion;
    }

    /**
     * 设置模组版本（用于测试不同版本的兼容性）
     *
     * @param version 版本字符串
     */
    public void setModVersion(String version) {
        this.modVersion = Objects.requireNonNull(version, "版本不能为 null");
    }

    /**
     * 获取渲染后端类型
     *
     * @return 后端类型枚举
     */
    public BackendType getBackendType() {
        return backendType;
    }

    /**
     * 设置渲染后端类型
     *
     * @param backend 后端类型
     */
    public void setBackendType(BackendType backend) {
        this.backendType = Objects.requireNonNull(backend, "后端类型不能为 null");
    }

    /**
     * 获取默认宽度
     *
     * @return 宽度（像素）
     */
    public int width() {
        return performanceSimulator.getWidth();
    }

    /**
     * 获取默认高度
     *
     * @return 高度（像素）
     */
    public int height() {
        return performanceSimulator.getHeight();
    }

    /**
     * 获取当前帧序号
     *
     * @return 帧序号（从 1 开始）
     */
    public int getFrameCount() {
        return frameCounter.get();
    }

    /**
     * 重置帧计数器
     */
    public void resetFrameCount() {
        frameCounter.set(0);
    }

    /**
     * 获取性能模拟器引用
     *
     * @return PerformanceSimulator 实例
     */
    public PerformanceSimulator getPerformanceSimulator() {
        return performanceSimulator;
    }

    /**
     * 获取已注册的 FBO 数量
     *
     * @return FBO 数量
     */
    public int getRegisteredFBOCount() {
        return fboRegistry.size();
    }

    /**
     * 获取已注册的纹理数量
     *
     * @return 纹理数量
     */
    public int getRegisteredTextureCount() {
        return textureRegistry.size();
    }

    // ==================== 内部类：性能模拟器 ====================

    /**
     * 性能模拟器
     * <p>
     * 用于模拟真实的帧渲染时间分布，
     * 支持不同的延迟模型（固定、正态分布、周期性等）。
     */
    public static class PerformanceSimulator {

        /** 默认分辨率宽度 */
        private final int width;

        /** 默认分辨率高度 */
        private final int height;

        /** 基础帧时间（纳秒），对应约 16.67ms (60fps) */
        private volatile long baseFrameTimeNs = 16_666_666L;

        /** 时间抖动范围（纳秒），±2ms */
        private volatile long jitterRangeNs = 2_000_000L;

        /** 延迟模型类型 */
        private volatile LatencyModel latencyModel = LatencyModel.STABLE;

        /** 随机数生成器（用于抖动） */
        private final Random random = new Random(42);  // 固定种子保证可重复性

        /**
         * 延迟模型类型
         */
        public enum LatencyModel {
            /** 固定帧时间（理想情况） */
            STABLE,
            /** 正态分布抖动（真实场景） */
            NORMAL_JITTER,
            /** 周期性卡顿（模拟 GC 或 IO） */
            PERIODIC_SPIKE,
            /** 渐进式恶化（模拟内存泄漏） */
            GRADUAL_DEGRADATION
        }

        /**
         * 创建性能模拟器
         *
         * @param width  默认宽度
         * @param height 默认高度
         */
        public PerformanceSimulator(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /**
         * 模拟一帧的渲染时间
         *
         * @param frameIndex 帧序号
         * @return 模拟的帧时间（纳秒）
         */
        public long simulateFrameTime(int frameIndex) {
            return switch (latencyModel) {
                case STABLE -> baseFrameTimeNs;
                case NORMAL_JITTER -> simulateNormalJitter();
                case PERIODIC_SPIKE -> simulatePeriodicSpike(frameIndex);
                case GRADUAL_DEGRADATION -> simulateGradualDegradation(frameIndex);
            };
        }

        /**
         * 设置基础帧时间
         *
         * @param frameTimeMs 帧时间（毫秒）
         */
        public void setBaseFrameTime(double frameTimeMs) {
            this.baseFrameTimeNs = (long) (frameTimeMs * 1_000_000.0);
        }

        /**
         * 设置抖动范围
         *
         * @param jitterMs 抖动范围（毫秒）
         */
        public void setJitterRange(double jitterMs) {
            this.jitterRangeNs = (long) (jitterMs * 1_000_000.0);
        }

        /**
         * 设置延迟模型
         *
         * @param model 延迟模型类型
         */
        public void setLatencyModel(LatencyModel model) {
            this.latencyModel = model;
        }

        /**
         * 获取宽度
         *
         * @return 宽度
         */
        public int getWidth() { return width; }

        /**
         * 获取高度
         *
         * @return 高度
         */
        public int getHeight() { return height; }

        // ---- 内部模拟方法 ----

        private long simulateNormalJitter() {
            long jitter = (long) (random.nextGaussian() * jitterRangeNs);
            return Math.max(baseFrameTimeNs - jitterRangeNs,
                    Math.min(baseFrameTimeNs + jitterRangeNs, baseFrameTimeNs + jitter));
        }

        private long simulatePeriodicSpike(int frameIndex) {
            // 每 60 帧产生一次尖峰（模拟 GC）
            if (frameIndex % 60 == 0) {
                return baseFrameTimeNs + 30_000_000L;  // +30ms 尖峰
            }
            return simulateNormalJitter();
        }

        private long simulateGradualDegradation(int frameIndex) {
            // 每帧增加微小延迟（模拟内存泄漏导致的性能下降）
            long degradation = (long) (frameIndex * 100L);  // 每帧 +0.1μs
            return baseFrameTimeNs + degradation + simulateNormalJitter() - baseFrameTimeNs;
        }
    }
}
