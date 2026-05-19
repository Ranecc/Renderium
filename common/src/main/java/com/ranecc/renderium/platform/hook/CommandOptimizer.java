package com.ranecc.renderium.platform.hook;

/** 命令优化器接口（Platform 层定义，Feature 层实现） */
public interface CommandOptimizer {
    boolean isEnabled();
    boolean tryMergeDrawCall(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);
}
