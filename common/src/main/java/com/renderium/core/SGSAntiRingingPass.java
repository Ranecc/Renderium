// ============================================================
// SGSAntiRingingPass - 向后兼容包装类 (Deprecated)
// ============================================================
// 此类已迁移至: com.renderium.core.render.SGSAntiRingingPass
//
// 迁移时间: 2026-04-25
// 迁移原因: Core 包重构 - 按功能域划分子包 (render)
//
// 使用方式:
//   旧代码（无需修改，但会收到编译警告）:
//     import com.renderium.core.SGSAntiRingingPass;
//     SGSAntiRingingPass.onSceneChange();
//
//   新代码（推荐迁移）:
//     import com.renderium.core.render.SGSAntiRingingPass;
//     SGSAntiRingingPass.onSceneChange();
//
// 删除计划: 此类将在 v7.0 中删除，请尽快迁移到新包路径。
// ============================================================

package com.renderium.core;

/**
 * SGS 零带宽抗振铃后处理系统（向后兼容包装类）
 * <p>
 * 此类仅为向后兼容而保留，所有静态方法调用均委托给
 * {@link com.renderium.core.render.SGSAntiRingingPass}。
 *
 * @deprecated 已迁移至 {@link com.renderium.core.render.SGSAntiRingingPass}
 *             请更新 import 路径。此类将在 v7.0 中删除。
 * @since 5.2.0
 * @see com.renderium.core.render.SGSAntiRingingPass
 */
@Deprecated(since = "6.0", forRemoval = true)
public final class SGSAntiRingingPass {

    // 防止实例化（与原始类保持一致的工具类模式）
    private SGSAntiRingingPass() {
        throw new UnsupportedOperationException("SGSAntiRingingPass 是静态工具类，不允许实例化");
    }

    // ==================== 常量委托 ====================

    /** 默认阻尼系数 k（委托） */
    public static final float DEFAULT_ATTRACT_K =
        com.renderium.core.render.SGSAntiRingingPass.DEFAULT_ATTRACT_K;

    /** 场景切换时的阻尼倍增因子（委托） */
    public static final float SCENE_CHANGE_MULTIPLIER =
        com.renderium.core.render.SGSAntiRingingPass.SCENE_CHANGE_MULTIPLIER;

    /** 衰减持续时间（帧数，委托） */
    public static final int DECAY_DURATION_FRAMES =
        com.renderium.core.render.SGSAntiRingingPass.DECAY_DURATION_FRAMES;

    /** 阻尼系数的安全上限（委托） */
    public static final float MAX_ATTRACT_K =
        com.renderium.core.render.SGSAntiRingingPass.MAX_ATTRACT_K;

    /** 阻尼系数的安全下限（委托） */
    public static final float MIN_ATTRACT_K =
        com.renderium.core.render.SGSAntiRingingPass.MIN_ATTRACT_K;

    /** 默认边缘强度阈值（委托） */
    public static final float DEFAULT_EDGE_THRESHOLD =
        com.renderium.core.render.SGSAntiRingingPass.DEFAULT_EDGE_THRESHOLD;

    // ==================== GLSL 源码访问接口委托 ====================

    /**
     * 获取完整的 GLSL 着色器源码（委托）
     *
     * @return 完整的 GLSL 450 core 着色器源码字符串
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static String getGlslSource() {
        return com.renderium.core.render.SGSAntiRingingPass.getGlslSource();
    }

    // ==================== 动态参数调整 API 委托 ====================

    /**
     * 通知 SGS Pass 发生了场景切换（委托）
     *
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static void onSceneChange() {
        com.renderium.core.render.SGSAntiRingingPass.onSceneChange();
    }

    /**
     * 每帧更新衰减计时器（委托）
     *
     * @return 长度为 4 的 float 数组，包含当前帧的 SGS 参数
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float[] updatePerFrame() {
        return com.renderium.core.render.SGSAntiRingingPass.updatePerFrame();
    }

    /**
     * 手动设置阻尼系数 k（委托）
     *
     * @param k 目标阻尼系数
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static void setAttractK(float k) {
        com.renderium.core.render.SGSAntiRingingPass.setAttractK(k);
    }

    /**
     * 获取当前有效的阻尼系数（委托）
     *
     * @return 当前阻尼系数 k
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float getCurrentK() {
        return com.renderium.core.render.SGSAntiRingingPass.getCurrentK();
    }

    /**
     * 获取当前衰减计时器值（委托）
     *
     * @return 衰减计时器
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static float getDecayTimer() {
        return com.renderium.core.render.SGSAntiRingingPass.getDecayTimer();
    }

    /**
     * 查询是否处于场景切换衰减状态（委托）
     *
     * @return true 表示正处于衰减期
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static boolean isInDecayPhase() {
        return com.renderium.core.render.SGSAntiRingingPass.isInDecayPhase();
    }

    // ==================== Vulkan 集成辅助接口委托 ====================

    /**
     * 创建 SGS Pass 所需的 Descriptor Set Layout（委托）
     *
     * @param device Vulkan 设备句柄持有者
     * @return DescriptorSetLayout 的 Vulkan 句柄
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static long createDescriptorLayout(
        com.renderium.core.render.VulkanDeviceHolder device) {
        return com.renderium.core.render.SGSAntiRingingPass.createDescriptorLayout(device);
    }

    /**
     * 创建 SGS Pass 的渲染管线（委托）
     *
     * @param device     Vulkan 设备句柄持有者
     * @param renderPass 渲染过程的 Vulkan 句柄
     * @return Pipeline 的 Vulkan 句柄
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static long createPipeline(
        com.renderium.core.render.VulkanDeviceHolder device, long renderPass) {
        return com.renderium.core.render.SGSAntiRingingPass.createPipeline(device, renderPass);
    }

    // ==================== 重置方法委托 ====================

    /**
     * 重置所有运行时状态到默认值（委托）
     *
     * @deprecated 请迁移到新包路径
     */
    @Deprecated
    public static void reset() {
        com.renderium.core.render.SGSAntiRingingPass.reset();
    }
}
