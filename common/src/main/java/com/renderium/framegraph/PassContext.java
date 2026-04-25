package com.renderium.framegraph;

import com.renderium.core.VulkanDeviceHolder;

/**
 * Renderium FrameGraph Pass 执行上下文
 * <p>
 * 封装每个 Pass 执行时所需的运行时信息，包括帧序号、时间信息、
 * Vulkan 设备句柄和视口尺寸等。此对象在每帧开始时由 {@code FrameGraphExecutor} 构建，
 * 并传递给所有注册的 Pass 进行渲染计算。
 * </p>
 *
 * <h3>设计模式：</h3>
 * <ul>
 *   <li>采用 Builder 模式构建，确保对象不可变性</li>
 *   <li>所有字段均为 final，一旦创建不可修改</li>
 *   <li>Builder 支持链式调用，提高可读性</li>
 * </ul>
 *
 * <h3>线程安全：</h3>
 * <ul>
 *   <li>PassContext 本身是不可变对象，天然线程安全</li>
 *   <li>Builder 不是线程安全的，应在单线程中构建</li>
 *   <li>可在多线程间安全传递和共享</li>
 * </ul>
 *
 * <h3>使用场景：</h3>
 * <pre>
 * // 在 FrameGraphExecutor 中构建上下文
 * PassContext context = new PassContext.Builder()
 *     .frameIndex(currentFrame)
 *     .deltaTime(frameTime)
 *     .vulkanDeviceHolder(VulkanDeviceHolder.getInstance())
 *     .width(windowWidth)
 *     .height(windowHeight)
 *     .build();
 *
 * // 在各 Pass 中使用
 * public void execute(PassContext ctx) {
 *     int frame = ctx.getFrameIndex();
 *     float dt = ctx.getDeltaTime();
 *     VulkanDeviceHolder device = ctx.getVulkanDeviceHolder();
 *     int w = ctx.getWidth();
 *     int h = ctx.getHeight();
 *
 *     // 基于 context 执行渲染逻辑...
 * }
 * </pre>
 *
 * <h3>生命周期：</h3>
 * <pre>
 * 每帧创建 → 传递给所有 Pass → 帧结束丢弃（不缓存复用）
 * </pre>
 *
 * @see com.renderium.framegraph.pass.EffectPipelinePass
 * @see com.renderium.framegraph.pass.SuperResolutionPass
 * @see com.renderium.framegraph.CachedFrameGraphExecutor
 * @since 5.2.0
 */
public final class PassContext {

    // ==================== 必填字段 ====================

    /**
     * 当前帧序号（从 0 开始递增）
     * 用于帧级同步、动画插值、历史帧数据索引等
     */
    private final int frameIndex;

    /**
     * 帧间隔时间（秒）
     * 用于物理模拟、动画平滑、时间相关效果计算
     * 典型值：60fps 时约为 0.0167f
     */
    private final float deltaTime;

    /**
     * Vulkan 设备句柄持有者引用
     * 提供底层 VkDevice、VMA allocator、图形/计算队列等原生资源
     */
    private final VulkanDeviceHolder vulkanDeviceHolder;

    // ==================== 可选字段（有默认值）====================

    /**
     * 帧宽度（像素）
     * 默认值：0（表示未设置或使用窗口尺寸）
     */
    private final int width;

    /**
     * 帧高度（像素）
     * 默认值：0（表示未设置或使用窗口尺寸）
     */
    private final int height;

    // ==================== 私有构造函数（仅由 Builder 调用）====================

    /**
     * 私有构造函数
     * 仅允许通过 Builder 创建实例，保证不可变性
     *
     * 【方法参数】
     * @param frameIndex        int - 当前帧序号（必须 >= 0）
     * @param deltaTime         float - 帧间隔时间（秒，必须 > 0）
     * @param vulkanDeviceHolder VulkanDeviceHolder - Vulkan 设备句柄持有者（不能为 null）
     * @param width             int - 帧宽度（像素，默认为 0）
     * @param height            int - 帧高度（像素，默认为 0）
     */
    private PassContext(
            int frameIndex,
            float deltaTime,
            VulkanDeviceHolder vulkanDeviceHolder,
            int width,
            int height) {

        this.frameIndex = frameIndex;
        this.deltaTime = deltaTime;
        this.vulkanDeviceHolder = vulkanDeviceHolder;
        this.width = width;
        this.height = height;
    }

    // ==================== Getter 方法 ====================

    /**
     * 获取当前帧序号
     *
     * 【返回值】
     * @return int - 当前帧序号（从 0 开始递增）
     *
     * 【用途】
     * - 帧级同步操作（如隔帧更新）
     * - 历史帧数据缓冲区索引
     * - 动画关键帧计算
     * - 调试日志中的帧标识
     */
    public int getFrameIndex() {
        return frameIndex;
    }

    /**
     * 获取帧间隔时间
     *
     * 【返回值】
     * @return float - 帧间隔时间（单位：秒），典型值 60fps ≈ 0.0167f
     *
     * 【用途】
     * - 物理模拟步进
     * - 动画插值平滑
     * - 时间相关的视觉效果（如运动模糊强度）
     * - FPS 计算和性能监控
     */
    public float getDeltaTime() {
        return deltaTime;
    }

    /**
     * 获取 Vulkan 设备句柄持有者
     *
     * 【返回值】
     * @return VulkanDeviceHolder - 全局单例的 Vulkan 设备句柄持有者引用
     *
     * 【用途】
     * - 获取 VkDevice 原生句柄用于 Streamline SDK 初始化
     * - 获取 VMA allocator 用于 GPU 显存分配
     * - 获取图形队列/计算队列用于命令提交
     * - 调用 VulkanDevice 高级 API
     *
     * @see com.renderium.core.VulkanDeviceHolder
     */
    public VulkanDeviceHolder getVulkanDeviceHolder() {
        return vulkanDeviceHolder;
    }

    /**
     * 获取帧宽度
     *
     * 【返回值】
     * @return int - 帧宽度（像素），默认值为 0 表示未设置
     *
     * 【用途】
     * - 视口/裁剪区域设置
     * - 纹理/帧缓冲区尺寸确定
     * - 屏幕空间坐标转换
     * - 分辨率相关的后处理参数调整
     */
    public int getWidth() {
        return width;
    }

    /**
     * 获取帧高度
     *
     * 【返回值】
     * @return int - 帧高度（像素），默认值为 0 表示未设置
     *
     * 【用途】
     * - 视口/裁剪区域设置
     * - 纹理/帧缓冲区尺寸确定
     * - 屏幕空间坐标转换
     * - 宽高比计算
     */
    public int getHeight() {
        return height;
    }

    // ==================== Builder 内部类 ====================

    /**
     * PassContext 构建器
     * <p>
     * 采用 Builder 模式创建 {@link PassContext} 实例。
     * 所有 setter 方法返回新的 Builder 实例（不可变 Builder 模式），
     * 确保在多步构建过程中不会意外修改已配置的状态。
     * </p>
     *
     * <h3>必填字段：</h3>
     * <ul>
     *   <li>{@code frameIndex} - 当前帧序号</li>
     *   <li>{@code deltaTime} - 帧间隔时间</li>
     *   <li>{@code vulkanDeviceHolder} - Vulkan 设备句柄持有者</li>
     * </ul>
     *
     * <h3>可选字段（带默认值）：</h3>
     * <ul>
     *   <li>{@code width} - 帧宽度，默认值 0</li>
     *   <li>{@code height} - 帧高度，默认值 0</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>
     * // 基本用法：设置所有必填字段 + 部分可选字段
     * PassContext ctx = new PassContext.Builder()
     *     .frameIndex(12345)
     *     .deltaTime(0.0167f)
     *     .vulkanDeviceHolder(VulkanDeviceHolder.getInstance())
     *     .width(1920)
     *     .height(1080)
     *     .build();
     *
     * // 最小化用法：仅设置必填字段（width/height 使用默认值 0）
     * PassContext minimalCtx = new PassContext.Builder()
     *     .frameIndex(0)
     *     .deltaTime(0.033f)
     *     .vulkanDeviceHolder(VulkanDeviceHolder.getInstance())
     *     .build();
     *
     * // 链式调用示例（Builder 不可变，每次返回新实例）
     * PassContext.Builder base = new PassContext.Builder()
     *     .frameIndex(frame)
     *     .deltaTime(dt)
     *     .vulkanDeviceHolder(device);
     *
     * PassContext fullCtx = base.width(2560).height(1440).build();
     * PassContext halfCtx = base.width(1280).height(720).build();  // base 未被修改
     * </pre>
     *
     * <h3>校验规则：</h3>
     * <ul>
     *   <li>{@code build()} 时校验所有必填字段是否已设置</li>
     *   <li>{@code frameIndex} 必须 >= 0</li>
     *   <li>{@code deltaTime} 必须 > 0 且 &lt;= 1.0（防止异常帧时间）</li>
     *   <li>{@code vulkanDeviceHolder} 不能为 null</li>
     *   <li>{@code width} 和 {@code height} 必须 >= 0（如果设置了的话）</li>
     * </ul>
     *
     * @since 5.2.0
     */
    public static final class Builder {

        /** 当前帧序号（必填） */
        private int frameIndex;

        /** 帧间隔时间（必填） */
        private float deltaTime;

        /** Vulkan 设备句柄持有者（必填） */
        private VulkanDeviceHolder vulkanDeviceHolder;

        /** 帧宽度（可选，默认 0） */
        private int width = 0;

        /** 帧高度（可选，默认 0） */
        private int height = 0;

        /** 标记 frameIndex 是否已设置 */
        private boolean frameIndexSet = false;

        /** 标记 deltaTime 是否已设置 */
        private boolean deltaTimeSet = false;

        /** 标记 vulkanDeviceHolder 是否已设置 */
        private boolean vulkanDeviceHolderSet = false;

        /**
         * 设置当前帧序号
         *
         * 【方法参数】
         * @param frameIndex int - 当前帧序号（必须 >= 0）
         *
         * 【返回值】
         * @return Builder - 新的 Builder 实例（不可变模式）
         *
         * @throws IllegalArgumentException 如果 frameIndex < 0
         */
        public Builder frameIndex(int frameIndex) {
            if (frameIndex < 0) {
                throw new IllegalArgumentException("frameIndex 必须 >= 0，实际值: " + frameIndex);
            }
            Builder copy = copyFields();
            copy.frameIndex = frameIndex;
            copy.frameIndexSet = true;
            return copy;
        }

        /**
         * 设置帧间隔时间
         *
         * 【方法参数】
         * @param deltaTime float - 帧间隔时间（秒，必须 > 0 且 <= 1.0）
         *
         * 【返回值】
         * @return Builder - 新的 Builder 实例（不可变模式）
         *
         * @throws IllegalArgumentException 如果 deltaTime <= 0 或 > 1.0
         */
        public Builder deltaTime(float deltaTime) {
            if (deltaTime <= 0 || deltaTime > 1.0f) {
                throw new IllegalArgumentException(
                    "deltaTime 必须在 (0, 1.0] 范围内，实际值: " + deltaTime);
            }
            Builder copy = copyFields();
            copy.deltaTime = deltaTime;
            copy.deltaTimeSet = true;
            return copy;
        }

        /**
         * 设置 Vulkan 设备句柄持有者
         *
         * 【方法参数】
         * @param vulkanDeviceHolder VulkanDeviceHolder - Vulkan 设备句柄持有者（不能为 null）
         *
         * 【返回值】
         * @return Builder - 新的 Builder 实例（不可变模式）
         *
         * @throws IllegalArgumentException 如果 vulkanDeviceHolder 为 null
         */
        public Builder vulkanDeviceHolder(VulkanDeviceHolder vulkanDeviceHolder) {
            if (vulkanDeviceHolder == null) {
                throw new IllegalArgumentException("vulkanDeviceHolder 不能为 null");
            }
            Builder copy = copyFields();
            copy.vulkanDeviceHolder = vulkanDeviceHolder;
            copy.vulkanDeviceHolderSet = true;
            return copy;
        }

        /**
         * 设置帧宽度
         *
         * 【方法参数】
         * @param width int - 帧宽度（像素，必须 >= 0，默认为 0）
         *
         * 【返回值】
         * @return Builder - 新的 Builder 实例（不可变模式）
         *
         * @throws IllegalArgumentException 如果 width < 0
         */
        public Builder width(int width) {
            if (width < 0) {
                throw new IllegalArgumentException("width 必须 >= 0，实际值: " + width);
            }
            Builder copy = copyFields();
            copy.width = width;
            return copy;
        }

        /**
         * 设置帧高度
         *
         * 【方法参数】
         * @param height int - 帧高度（像素，必须 >= 0，默认为 0）
         *
         * 【返回值】
         * @return Builder - 新的 Builder 实例（不可变模式）
         *
         * @throws IllegalArgumentException 如果 height < 0
         */
        public Builder height(int height) {
            if (height < 0) {
                throw new IllegalArgumentException("height 必须 >= 0，实际值: " + height);
            }
            Builder copy = copyFields();
            copy.height = height;
            return copy;
        }

        /**
         * 构建 PassContext 实例
         * <p>
         * 校验所有必填字段是否已正确设置，然后创建不可变的 PassContext 对象。
         * 此方法应作为链式调用的最后一步调用。
         * </p>
         *
         * 【返回值】
         * @return PassContext - 构建完成的不可变执行上下文对象
         *
         * 【异常】
         * @throws IllegalStateException 如果任何必填字段未设置
         *
         * 【校验规则】
         * <ol>
         *   <li>frameIndex 必须已设置（通过 frameIndex() 方法）</li>
         *   <li>deltaTime 必须已设置（通过 deltaTime() 方法）</li>
         *   <li>vulkanDeviceHolder 必须已设置（通过 vulkanDeviceHolder() 方法）</li>
         * </ol>
         *
         * 【性能特征】
         * - 仅做简单的布尔检查，无 I/O 或复杂计算
         * - 对象创建开销极小（5 个 final 字段赋值）
         * - 适合每帧调用一次
         */
        public PassContext build() {
            // 校验必填字段
            if (!frameIndexSet) {
                throw new IllegalStateException("frameIndex 是必填字段，请调用 frameIndex() 设置");
            }
            if (!deltaTimeSet) {
                throw new IllegalStateException("deltaTime 是必填字段，请调用 deltaTime() 设置");
            }
            if (!vulkanDeviceHolderSet) {
                throw new IllegalStateException("vulkanDeviceHolder 是必填字段，请调用 vulkanDeviceHolder() 设置");
            }

            // 创建不可变实例
            return new PassContext(
                    this.frameIndex,
                    this.deltaTime,
                    this.vulkanDeviceHolder,
                    this.width,
                    this.height);
        }

        /**
         * 复制当前 Builder 的所有字段到新实例
         * <p>
         * 实现 Builder 的不可变模式：每次 setter 调用都返回一个全新的 Builder 实例，
         * 原始 Builder 保持不变，支持安全的链式调用和分支构建。
         * </p>
         *
         * 【返回值】
         * @return Builder - 包含当前所有字段值的全新 Builder 实例
         *
         * 【实现要点】
         * - 复制所有字段值（包括 set 标志位）
         * - 返回新实例，不影响原 Builder
         * - 时间复杂度 O(1)，仅涉及基本类型复制
         */
        private Builder copyFields() {
            Builder copy = new Builder();
            copy.frameIndex = this.frameIndex;
            copy.deltaTime = this.deltaTime;
            copy.vulkanDeviceHolder = this.vulkanDeviceHolder;
            copy.width = this.width;
            copy.height = this.height;
            copy.frameIndexSet = this.frameIndexSet;
            copy.deltaTimeSet = this.deltaTimeSet;
            copy.vulkanDeviceHolderSet = this.vulkanDeviceHolderSet;
            return copy;
        }
    }
}
