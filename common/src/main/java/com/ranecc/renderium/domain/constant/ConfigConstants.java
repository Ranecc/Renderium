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
}
