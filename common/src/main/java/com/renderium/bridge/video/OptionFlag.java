package com.renderium.bridge.video;

import net.minecraft.resources.Identifier;
import java.util.Locale;

/**
 * 选项变更标志枚举
 * <p>
 * 用于标识修改某个选项后需要执行的特定操作。
 * 多个标志可以组合使用，表示需要执行多个操作。
 *
 * <h3>标志说明：</h3>
 * <ul>
 *   <li>{@link #REQUIRES_RENDERER_RELOAD} - 需要重新加载渲染器</li>
 *   <li>{@link #REQUIRES_GAME_RESTART} - 需要重启游戏</li>
 *   <li>{@link #REQUIRES_VIDEOMODE_RELOAD} - 需要重新加载视频模式</li>
 *   <li>{@link #REQUIRES_ASSET_RELOAD} - 需要重新加载资源包</li>
 * </ul>
 *
 * <h4>使用示例：</h4>
 * <pre>{@code
 * builder.createBooleanOption(id)
 *     .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD,
 *               OptionFlag.REQUIRES_VIDEOMODE_RELOAD);
 * }</pre>
 *
 * @see BooleanOptionBuilder#setFlags(OptionFlag...)
 * @see IntegerOptionBuilder#setFlags(OptionFlag...)
 * @see EnumOptionBuilder#setFlags(OptionFlag...)
 * @since 1.0.0
 */
public enum OptionFlag {

    /**
     * 需要重新加载渲染器
     * <p>
     * 表示修改此选项后需要丢弃并重建所有网格数据。
     * 此标志应谨慎使用，因为重建所有网格是一个破坏性操作。
     * <p>
     * 适用场景：切换渲染后端、修改着色器设置等。
     */
    REQUIRES_RENDERER_RELOAD,

    /**
     * 需要重启游戏
     * <p>
     * 表示修改此选项后必须完全重启游戏才能生效。
     * 这是最严格的标志，通常只用于核心架构级别的更改。
     * <p>
     * 适用场景：修改内存分配策略、切换核心算法等。
     */
    REQUIRES_GAME_RESTART,

    /**
     * 需要重新加载视频模式
     * <p>
     * 表示修改此选项后需要更新视频模式（分辨率、刷新率等）。
     * 这会导致短暂的画面中断。
     * <p>
     * 适用场景：修改全屏模式、垂直同步、分辨率缩放等。
     */
    REQUIRES_VIDEOMODE_RELOAD,

    /**
     * 需要重新加载资源
     * <p>
     * 表示修改此选项后需要重新加载资源包，
     * 然后重新加载渲染器（导致所有网格被丢弃并重建）。
     * <p>
     * 适用场景：修改纹理质量、资源包选择、材质设置等。
     */
    REQUIRES_ASSET_RELOAD;

    /** 此标志的唯一标识符（命名空间:renderium + 标志名称） */
    private final Identifier id = Identifier.fromNamespaceAndPath(
            "renderium",
            "builtin_option_flag." + this.name().toLowerCase(Locale.ROOT)
    );

    /**
     * 获取此标志的唯一标识符
     * <p>
     * 返回的 Identifier 格式为 {@code renderium:builtin_option_flag.xxx}，
     * 可用于日志记录、序列化或跨模块引用。
     *
     * @return 此标志的 Identifier 实例
     */
    public Identifier getId() {
        return this.id;
    }
}
