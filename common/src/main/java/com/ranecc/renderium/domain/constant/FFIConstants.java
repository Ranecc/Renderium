package com.ranecc.renderium.domain.constant;

public final class FFIConstants {

    private FFIConstants() {}

    public static final String NATIVE_LIBRARY_NAME = "renderium_accel";

    public static final int HANDLE_NULL = 0;
    public static final long POINTER_NULL = 0L;

    public static final int VISIBILITY_HEADER_SIZE = 16;
    public static final int LOD_ENTRY_SIZE = 12;

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_ERROR_INVALID_HANDLE = -1;
    public static final int RESULT_ERROR_NOT_INITIALIZED = -2;
    public static final int RESULT_ERROR_OUT_OF_MEMORY = -3;
}
