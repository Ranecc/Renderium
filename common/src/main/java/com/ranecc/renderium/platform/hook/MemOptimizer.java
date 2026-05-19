package com.ranecc.renderium.platform.hook;

/** 内存优化器接口（Platform 层定义，Feature 层实现） */
public interface MemOptimizer {
    boolean isEnabled();
    Object allocateFromPerFrameArena(java.util.function.Supplier<String> label, int usage, long size);
    Object allocateFromRingBufferArena(java.util.function.Supplier<String> label, int usage, long size);
    Object allocateFromPoolArena(java.util.function.Supplier<String> label, int usage, long size);
}
