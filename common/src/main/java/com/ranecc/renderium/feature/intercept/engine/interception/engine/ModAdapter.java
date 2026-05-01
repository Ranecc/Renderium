package com.ranecc.renderium.feature.intercept.engine.interception.engine;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.logging.Logger;

/**
 * 模组适配器抽象基类
 * <p>
 * 用于检测第三方渲染模组并将其输出适配到 26.2-snapshot-3 的 FrameGraph 资源流中。
 * 提供默认的纹理 Handle 提取工具方法，子类可按需覆写。
 * </p>
 *
 * <h3>设计背景：</h3>
 * <p>
 * 在 26.2-snapshot-3 中，所有模组都通过 Blaze3D 的 Vulkan 后端渲染，
 * 不再需要 FBO 拦截或 GL→VK 互操作。ModAdapter 的职责是：
 * </p>
 * <ul>
 *   <li>检测模组是否已加载</li>
 *   <li>获取模组输出的颜色/深度纹理 Handle（FrameGraph Resource）</li>
 *   <li>根据模组特性调整 FrameGraph Pass 配置</li>
 * </ul>
 *
 * <h3>内置工具方法：</h3>
 * <ul>
 *   <li>{@link #extractOutputColorTextureHandle()} - 从 RenderSystem 提取输出颜色纹理 Handle</li>
 *   <li>{@link #extractTextureHandle(Object)} - 从 GpuTexture 对象提取原生 Handle</li>
 * </ul>
 *
 * @see DefaultModDetectionEngine
 * @see com.mojang.blaze3d.framegraph.FrameGraphBuilder
 * @since 5.2.0
 */
public abstract class ModAdapter {

    /** 日志记录器 */
    protected static final Logger LOGGER = Logger.getLogger("Renderium|ModAdapter");

    // ==================== 抽象方法（子类必须实现）====================

    /**
     * 检测指定模组是否已加载
     *
     * 【方法参数】
     * @param modId String - 模组标识符（如 "iris" / "oculus"）
     *
     * 【返回值】
     * @return boolean - true 表示模组存在且可用，false 表示不存在
     */
    public abstract boolean isModLoaded(String modId);

    /**
     * 调整 FrameGraph 以适配模组的特殊需求
     * <p>
     * 某些模组可能需要额外的 Pass 或资源：
     * </p>
     * <ul>
     *   <li><b>Iris</b>: 需要额外 Shadow Map Pass</li>
     *   <li><b>Oculus</b>: 可能需要 VR 特定 Pass</li>
     * </ul>
     *
     * 【方法参数】
     * @param builder FrameGraphBuilder - 当前帧图构建器实例
     *
     * 【返回值】void
     */
    public abstract void adaptFrameGraph(FrameGraphBuilder builder);

    // ==================== 默认实现（可覆写）====================

    /**
     * 获取模组的主输出颜色纹理 Handle（默认实现）
     * <p>
     * 默认从 {@link RenderSystem#outputColorTextureOverride} 获取。
     * 子类可覆写此方法以使用模组特定的获取逻辑。
     * </p>
     *
     * 【返回值】
     * @return long - 颜色纹理的 FrameGraph Resource Handle，
     *               如果不可用返回 0L
     */
    public long getModOutputColorTexture() {
        try {
            Object overrideField = extractOutputColorTextureOverride();
            if (overrideField != null) {
                return extractTextureHandle(overrideField);
            }
        } catch (Exception e) {
            LOGGER.warning("无法获取输出颜色纹理: " + e.getMessage());
        }
        return 0L;
    }

    /**
     * 获取模组的深度纹理 Handle（默认实现）
     * <p>
     * 默认返回 0L（大多数模组的深度由 Blaze3D 统一管理）。
     * 子类可覆写此方法以提供模组特有的深度纹理。
     * </p>
     *
     * 【返回值】
     * @return long - 深度纹理 Handle，如果不可用返回 0L
     */
    public long getModDepthTexture() {
        return 0L;
    }

    // ==================== 静态工具方法（供所有适配器共用）====================

    /**
     * 通过反射从 RenderSystem 提取 outputColorTextureOverride 字段值
     * <p>
     * 26.2 中模组的渲染输出直接写入此字段，
     * 是获取模组输出纹理的标准路径。
     * </p>
     *
     * 【返回值】
     * @return Object - outputColorTextureOverride 字段值（GpuTexture 实例），失败返回 null
     */
    protected static Object extractOutputColorTextureOverride() {
        try {
            java.lang.reflect.Field field = RenderSystem.class.getDeclaredField("outputColorTextureOverride");
            field.setAccessible(true);
            return field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOGGER.fine("outputColorTextureOverride 字段未找到: " + e.getMessage());

            // 尝试备用字段名
            try {
                java.lang.reflect.Field altField = RenderSystem.class.getDeclaredField("outputTextureOverride");
                altField.setAccessible(true);
                return altField.get(null);
            } catch (Exception ex) {
                LOGGER.fine("备用字段 outputTextureOverride 也未找到");
                return null;
            }
        }
    }

    /**
     * 从 GpuTexture 对象中提取原生纹理 Handle
     * <p>
     * 按优先级尝试以下字段：handle → id → toString 解析。
     * </p>
     *
     * 【方法参数】
     * @param textureObject Object - GpuTexture 实例（不能为 null）
     *
     * 【返回值】
     * @return long - 原生纹理 Handle（LWJGL 地址），提取失败返回 0L
     *
     * 【异常处理】
     * - 所有反射异常被捕获并记录 WARNING 日志
     * - 不抛出异常，保证调用方安全
     */
    protected static long extractTextureHandle(Object textureObject) {
        if (textureObject == null) {
            return 0L;
        }

        try {
            java.lang.Class<?> clazz = textureObject.getClass();

            // 策略1：尝试 handle 字段
            try {
                java.lang.reflect.Field handleField = clazz.getDeclaredField("handle");
                handleField.setAccessible(true);
                Object value = handleField.get(textureObject);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // 字段不存在或无法访问，继续尝试下一个策略
            }

            // 策略2：尝试 id 字段
            try {
                java.lang.reflect.Field idField = clazz.getDeclaredField("id");
                idField.setAccessible(true);
                Object value = idField.get(textureObject);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // 字段不存在或无法访问，继续尝试下一个策略
            }

            // 策略3：toString 解析（最后手段，查找 0x 十六进制地址）
            String str = textureObject.toString();
            int idx = str.lastIndexOf("0x");
            if (idx >= 0 && idx + 2 < str.length()) {
                String hexStr = str.substring(idx + 2).split("[^0-9a-fA-F]")[0];
                if (!hexStr.isEmpty()) {
                    return Long.parseLong(hexStr, 16);
                }
            }

            LOGGER.fine("无法从 GpuTexture 提取 Handle: " + clazz.getSimpleName());
            return 0L;

        } catch (Exception e) {
            LOGGER.warning("提取纹理 Handle 异常: " + e.getMessage());
            return 0L;
        }
    }
}
