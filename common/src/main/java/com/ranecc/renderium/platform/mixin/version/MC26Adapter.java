package com.ranecc.renderium.platform.mixin.version;

/**
 * MC 26.2 版本适配器 - 最小化设计
 * <p>
 * 当 MC 版本升级时, 仅修改此类中的方法名映射,
 * 所有 Mixin 不需要改动。
 * <p>
 * 使用方式: Mixin @Inject 注解中的 method 属性引用此类常量,
 * 避免在 Mixin 类中硬编码 MC 方法名。
 */
public final class MC26Adapter {
    private MC26Adapter() {}

    /** GameRenderer 的帧渲染方法名 */
    public static final String GAME_RENDERER_RENDER = "render";

    /** LevelRenderer 的世界渲染方法名（MC 26.2 实际 API） */
    public static final String LEVEL_RENDERER_RENDER = "renderLevel";

    /** RenderSystem 的初始化方法名 */
    public static final String RENDER_SYSTEM_INIT = "initRenderer";

    /** FrameGraphBuilder 的执行方法名 */
    public static final String FRAME_GRAPH_EXECUTE = "execute";

    /** GpuDevice 的创建缓冲区方法名 */
    public static final String GPU_DEVICE_CREATE_BUFFER = "createBuffer";

    /** Window 的构造函数 */
    public static final String WINDOW_CONSTRUCTOR = "<init>";

    /** DeltaTracker 获取帧时间的方法名 */
    public static final String GET_REALTIME_DELTA = "getRealtimeDeltaTicks";
}
