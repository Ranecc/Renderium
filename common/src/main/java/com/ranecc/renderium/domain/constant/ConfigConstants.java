package com.ranecc.renderium.domain.constant;

public final class ConfigConstants {

    private ConfigConstants() {}

    public static final float DEFAULT_GPU_USAGE_THRESHOLD = 0.7f;
    public static final float MIN_GPU_THRESHOLD = 0.3f;
    public static final float MAX_GPU_THRESHOLD = 0.95f;

    public static final int DEFAULT_BFS_MAX_NODES = 8192;
    public static final int BFS_RESULT_HEADER_SIZE = 16;

    public static final int LOD_MAX_LEVELS = 4;

    public static final float DEFAULT_FOV = 70.0f;
    public static final float DEFAULT_NEAR_PLANE = 0.05f;
    public static final float DEFAULT_FAR_PLANE = 1000.0f;

    public static final int DEFAULT_WINDOW_WIDTH = 1920;
    public static final int DEFAULT_WINDOW_HEIGHT = 1080;

    public static final int HISTORY_MATRIX_COUNT = 16;

    // ==================== Mesh Build / RenderOpt Constants ====================

    /** 最大批处理大小 (DrawCallBatcher) */
    public static final int MAX_BATCH_SIZE = 1024;

    /** 最大网格构建 Worker 数 (MeshBuildScheduler) */
    public static final int MAX_MESH_BUILD_WORKERS = 8;

    /** 为渲染线程保留的核心数 */
    public static final int RESERVED_CORES_FOR_RENDER_THREAD = 1;

    /** 网格构建队列容量 (MeshBuildScheduler) */
    public static final int MESH_BUILD_QUEUE_CAPACITY = 1024;

    /** 关闭超时秒数 */
    public static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    /** 小缓冲区大小 */
    public static final int SMALL_BUFFER_SIZE = 64;
}
