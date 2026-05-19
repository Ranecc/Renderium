package com.ranecc.renderium.feature.renderopt;

import com.ranecc.renderium.infrastructure.gpu.NativeLibraryLoader;
import java.util.logging.Logger;

public class RenderiumAccelerator {
    private static final Logger LOGGER = Logger.getLogger(RenderiumAccelerator.class.getName());

    /** 原生加速库名称（对应 cmake 输出的 renderium_accel.dll/so/dylib） */
    private static final String NATIVE_LIB_NAME = "renderium_accel";

    private static final RenderiumAccelerator INSTANCE = new RenderiumAccelerator();

    /** 检测结果缓存（类加载时执行一次） */
    private final boolean available;

    private RenderiumAccelerator() {
        boolean detected;
        try {
            detected = NativeLibraryLoader.getInstance().load(NATIVE_LIB_NAME);
            if (detected) {
                LOGGER.info("✓ 原生加速库加载成功: " + NATIVE_LIB_NAME);
            } else {
                LOGGER.info("原生加速库不可用: " + NATIVE_LIB_NAME + "（回退到 Java 实现）");
            }
        } catch (Exception | UnsatisfiedLinkError e) {
            LOGGER.fine("原生加速库加载失败（非关键，使用 Java 回退）: " + e.getMessage());
            detected = false;
        }
        this.available = detected;
    }

    public static RenderiumAccelerator getInstance() { return INSTANCE; }

    public boolean isAvailable() { return available; }
}
