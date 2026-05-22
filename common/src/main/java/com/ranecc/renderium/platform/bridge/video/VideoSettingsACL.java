package com.ranecc.renderium.platform.bridge.video;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.client.OptionInstance;

/**
 * 视频设置 ACL 防腐层
 *
 * <p>隔离 Mixin 层与 Feature 层，Mixin 只调用 ACL 静态方法，
 * Feature 层通过 registerXxx() 注入函数式接口回调。
 *
 * <h3>设计约束：</h3>
 * <ul>
 *   <li>不 import 任何 Feature 层类（RenderiumConfig, VulkanFFMDebugger 等）</li>
 *   <li>通过 volatile Supplier 函数式接口回调，零反射</li>
 *   <li>性能 &lt; 10ns（直接调用函数式接口）</li>
 * </ul>
 *
 * <h3>方法签名：</h3>
 * <ul>
 *   <li>{@link #getRenderiumHeader()} - 获取 Renderium 分区标题组件</li>
 *   <li>{@link #getDebugHeader()} - 获取 Debug 分区标题组件</li>
 *   <li>{@link #createRenderiumOptions()} - 创建 Renderium 选项数组</li>
 *   <li>{@link #createDebugOptions()} - 创建 Debug 选项数组</li>
 *   <li>{@link #isReady()} - 检查 ACL 是否已就绪</li>
 *   <li>{@link #registerRenderiumOptions(Supplier)} - 注册 Renderium 选项工厂</li>
 *   <li>{@link #registerDebugOptions(Supplier)} - 注册 Debug 选项工厂</li>
 * </ul>
 */
public final class VideoSettingsACL {

    // ===== 函数式接口回调（由 PlatformInitializer 注册） =====

    /** 创建 Renderium 选项数组的工厂 */
    private static volatile Supplier<OptionInstance<?>[]> renderiumOptionsSupplier;

    /** 创建 Debug 选项数组的工厂 */
    private static volatile Supplier<OptionInstance<?>[]> debugOptionsSupplier;

    private VideoSettingsACL() {}

    // ===== ACL API（Mixin 层调用） =====

    /**
     * 获取 Renderium 分区标题
     * @return 翻译后的标题组件
     */
    public static Component getRenderiumHeader() {
        return Component.translatable("renderium.section.renderium");
    }

    /**
     * 获取 Debug 分区标题
     * @return 翻译后的标题组件
     */
    public static Component getDebugHeader() {
        return Component.translatable("renderium.section.debug");
    }

    /**
     * 创建 Renderium 选项数组
     * @return OptionInstance 数组；如果未注册回调则返回空数组
     */
    public static OptionInstance<?>[] createRenderiumOptions() {
        Supplier<OptionInstance<?>[]> supplier = renderiumOptionsSupplier;
        return supplier != null ? supplier.get() : new OptionInstance<?>[0];
    }

    /**
     * 创建 Debug 选项数组
     * @return OptionInstance 数组；如果未注册回调则返回空数组
     */
    public static OptionInstance<?>[] createDebugOptions() {
        Supplier<OptionInstance<?>[]> supplier = debugOptionsSupplier;
        return supplier != null ? supplier.get() : new OptionInstance<?>[0];
    }

    /**
     * 检查 ACL 是否已就绪（回调已注册）
     * @return true 如果 renderiumOptionsSupplier 已注册
     */
    public static boolean isReady() {
        return renderiumOptionsSupplier != null;
    }

    // ===== 回调注册（PlatformInitializer 调用） =====

    /**
     * 注册 Renderium 选项工厂
     * @param supplier 创建 OptionInstance 数组的工厂
     * @throws NullPointerException 如果 supplier 为 null
     */
    public static void registerRenderiumOptions(Supplier<OptionInstance<?>[]> supplier) {
        renderiumOptionsSupplier = Objects.requireNonNull(supplier, "renderiumOptionsSupplier must not be null");
    }

    /**
     * 注册 Debug 选项工厂
     * @param supplier 创建 OptionInstance 数组的工厂
     * @throws NullPointerException 如果 supplier 为 null
     */
    public static void registerDebugOptions(Supplier<OptionInstance<?>[]> supplier) {
        debugOptionsSupplier = Objects.requireNonNull(supplier, "debugOptionsSupplier must not be null");
    }
}
