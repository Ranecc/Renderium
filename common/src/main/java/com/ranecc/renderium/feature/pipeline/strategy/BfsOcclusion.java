package com.ranecc.renderium.feature.pipeline.strategy;

// Renderium BFS Occlusion FFI 接口
// 业务链位置: NativeBfsStrategy
//               → BfsOcclusion (此接口)
//               → BfsOcclusionFFIAdapter (实现)
//               → renderium_accel.dll (C++ 原生库)
//
// 作用: 定义 C++ 原生库的 BFS 遮挡剔除 FFI 接口 (Panama FFM)。
//       仅 Native 路径使用，Java 路径直接调用 BfsOcclusionEngine。
//
// 方法: createContext / findVisible / destroyContext
//       对应 C++ 侧 renderium_accel 库的导出函数。

public interface BfsOcclusion {
    long createContext(int maxSections);
    int[] findVisible(long context, float cameraX, float cameraY, float cameraZ,
                      float fov, float renderDistance, int frameNumber);
    void destroyContext(long context);
}
