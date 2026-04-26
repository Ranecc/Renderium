// Renderium - 轻量 MC 抽象层
// 帧数据快照 - 每帧由 LifecycleManager 更新，供代码层零 Mixin 依赖查询

package com.renderium.bridge.mc;

/**
 * 渲染帧数据快照（不可变）
 * <p>
 * 由 {@link RenderiumLifecycleManager} 在每个生命周期钩子中填充，
 * 通过 {@link MCRenderBridge#getCurrentFrameData()} 供代码层查询。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>零分配：ThreadLocal 预分配，每帧 reset 复用</li>
 *   <li>零锁：volatile 发布，无 synchronized</li>
 *   <li>零 Mixin 依赖：代码层通过此对象获取所有 MC 渲染状态</li>
 * </ul>
 *
 * <h3>数据来源映射：</h3>
 * <pre>
 * op② updateGlobalUniforms → windowWidth/Height, gameTick, cameraX/Y/Z
 * op⑥ setProjectionMatrix  → projectionMatrix[16], fov, nearPlane, farPlane
 * op⑦ updateFogBuffer       → fogColor[4], fogStart, fogEnd, fogDensity, fogType
 * op⑨ repositionCamera      → cameraX/Y/Z, yaw, pitch, viewAreaChanged
 * op⑩ setModelViewMatrix    → viewMatrix[16]
 * op⑭ prepareChunkRenders   → visibleSectionCount, totalSectionCount,
 *                               opaqueDrawCallCount, translucentDrawCallCount
 * </pre>
 *
 * @see MCRenderBridge
 * @see RenderiumLifecycleManager
 * @since 1.0.0
 */
public final class FrameDataSnapshot {

    // ==================== 矩阵数据（16 floats，column-major） ====================

    /** 投影矩阵（来自 op⑥ setProjectionMatrix） */
    private final float[] projectionMatrix;

    /** 视图矩阵（来自 op⑩ setModelViewMatrix） */
    private final float[] viewMatrix;

    /** 预计算的 VP 矩阵 = view × projection */
    private final float[] viewProjectionMatrix;

    /** 逆视图矩阵（用于光照计算） */
    private final float[] invViewMatrix;

    /** 历史视图矩阵环形缓冲区（16帧，用于 TAA/MotionBlur） */
    private final float[][] previousViewMatrices;

    /** 环形缓冲区写入位置 */
    private int historyWriteIndex;

    // ==================== 相机状态（来自 op②/op⑨） ====================

    /** 相机世界坐标 X */
    private float cameraX;

    /** 相机世界坐标 Y */
    private float cameraY;

    /** 相机世界坐标 Z */
    private float cameraZ;

    /** 相机偏航角（弧度） */
    private float yaw;

    /** 相机俯仰角（弧度） */
    private float pitch;

    /** 视野角度（度数） */
    private float fov;

    /** 近裁剪面距离 */
    private float nearPlane;

    /** 远裁剪面距离 */
    private float farPlane;

    // ==================== 窗口/时序（来自 op②） ====================

    /** 窗口宽度（像素） */
    private int windowWidth;

    /** 窗口高度（像素） */
    private int windowHeight;

    /** 游戏刻 */
    private long gameTick;

    // ==================== 雾效数据（来自 op⑦） ====================

    /** 雾颜色 RGBA */
    private final float[] fogColor;

    /** 雾起始距离 */
    private float fogStart;

    /** 雾终止距离 */
    private float fogEnd;

    /** 雾密度 */
    private float fogDensity;

    /** 雾类型（0=线性, 1=指数, 2=指数平方） */
    private int fogType;

    /** 雾效是否启用 */
    private boolean fogEnabled;

    // ==================== 区块可见性（来自 op⑭） ====================

    /** 可见区块段数量 */
    private int visibleSectionCount;

    /** 总区块段数量 */
    private int totalSectionCount;

    /** 不透明绘制调用数 */
    private int opaqueDrawCallCount;

    /** 半透明绘制调用数 */
    private int translucentDrawCallCount;

    /** 视野区域是否变化 */
    private boolean viewAreaChanged;

    // ==================== 帧序号 ====================

    /** 帧序号（单调递增） */
    private int frameIndex;

    // ==================== 构造函数 ====================

    /**
     * 构造帧数据快照
     * <p>
     * 预分配所有数组，后续通过 setter 方法更新。
     * 配合 ThreadLocal 使用，避免每帧 GC 压力。
     */
    public FrameDataSnapshot() {
        this.projectionMatrix = new float[16];
        this.viewMatrix = new float[16];
        this.viewProjectionMatrix = new float[16];
        this.invViewMatrix = new float[16];
        this.previousViewMatrices = new float[16][16];
        this.historyWriteIndex = 0;
        this.fogColor = new float[4];

        // 初始化投影矩阵为单位矩阵
        identityMatrix(projectionMatrix);
        identityMatrix(viewMatrix);
        identityMatrix(viewProjectionMatrix);
        identityMatrix(invViewMatrix);
        for (float[] mat : previousViewMatrices) {
            identityMatrix(mat);
        }
    }

    // ==================== Getter 方法：矩阵 ====================

    /**
     * 获取投影矩阵
     *
     * @return 投影矩阵（16 floats，column-major），直接引用，不拷贝
     */
    public float[] getProjectionMatrix() { return projectionMatrix; }

    /**
     * 获取视图矩阵
     *
     * @return 视图矩阵（16 floats，column-major），直接引用，不拷贝
     */
    public float[] getViewMatrix() { return viewMatrix; }

    /**
     * 获取预计算的 VP 矩阵
     *
     * @return VP 矩阵（16 floats，column-major），直接引用，不拷贝
     */
    public float[] getViewProjectionMatrix() { return viewProjectionMatrix; }

    /**
     * 获取逆视图矩阵
     *
     * @return 逆视图矩阵（16 floats，column-major），直接引用，不拷贝
     */
    public float[] getInvViewMatrix() { return invViewMatrix; }

    /**
     * 获取历史视图矩阵环形缓冲区
     *
     * @return 16 帧历史视图矩阵数组，直接引用
     */
    public float[][] getPreviousViewMatrices() { return previousViewMatrices; }

    // ==================== Getter 方法：相机 ====================

    public float getCameraX() { return cameraX; }
    public float getCameraY() { return cameraY; }
    public float getCameraZ() { return cameraZ; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getFov() { return fov; }
    public float getNearPlane() { return nearPlane; }
    public float getFarPlane() { return farPlane; }

    // ==================== Getter 方法：窗口/时序 ====================

    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public long getGameTick() { return gameTick; }

    // ==================== Getter 方法：雾效 ====================

    public float[] getFogColor() { return fogColor; }
    public float getFogStart() { return fogStart; }
    public float getFogEnd() { return fogEnd; }
    public float getFogDensity() { return fogDensity; }
    public int getFogType() { return fogType; }
    public boolean isFogEnabled() { return fogEnabled; }

    // ==================== Getter 方法：区块可见性 ====================

    public int getVisibleSectionCount() { return visibleSectionCount; }
    public int getTotalSectionCount() { return totalSectionCount; }
    public int getOpaqueDrawCallCount() { return opaqueDrawCallCount; }
    public int getTranslucentDrawCallCount() { return translucentDrawCallCount; }
    public boolean isViewAreaChanged() { return viewAreaChanged; }

    /**
     * 获取区块数据（自身引用，用于兼容旧 API）
     *
     * @return 帧数据快照自身
     */
    public FrameDataSnapshot getChunkData() { return this; }

    // ==================== Getter 方法：帧序号 ====================

    public int getFrameIndex() { return frameIndex; }

    // ==================== Setter 方法（仅供 LifecycleManager 调用） ====================

    /**
     * 设置投影矩阵（来自 op⑥）
     *
     * @param src 16 floats，column-major
     */
    public void setProjectionMatrix(float[] src) {
        System.arraycopy(src, 0, projectionMatrix, 0, 16);
    }

    /**
     * 设置视图矩阵（来自 op⑩），同时推入历史缓冲区
     *
     * @param src 16 floats，column-major
     */
    public void setViewMatrix(float[] src) {
        // 推入历史缓冲区（先保存当前值）
        float[] historySlot = previousViewMatrices[historyWriteIndex];
        System.arraycopy(viewMatrix, 0, historySlot, 0, 16);
        historyWriteIndex = (historyWriteIndex + 1) & 0x0F; // mod 16

        // 更新当前视图矩阵
        System.arraycopy(src, 0, viewMatrix, 0, 16);
    }

    /**
     * 设置逆视图矩阵
     *
     * @param src 16 floats，column-major
     */
    public void setInvViewMatrix(float[] src) {
        System.arraycopy(src, 0, invViewMatrix, 0, 16);
    }

    /**
     * 重新计算 VP 矩阵 = view × projection
     */
    public void recomputeVPMatrix() {
        multiplyMatrix4x4(viewMatrix, projectionMatrix, viewProjectionMatrix);
    }

    public void setCameraPosition(float x, float y, float z) {
        this.cameraX = x; this.cameraY = y; this.cameraZ = z;
    }

    public void setCameraRotation(float yaw, float pitch) {
        this.yaw = yaw; this.pitch = pitch;
    }

    public void setFov(float fov) { this.fov = fov; }
    public void setNearPlane(float near) { this.nearPlane = near; }
    public void setFarPlane(float far) { this.farPlane = far; }

    public void setWindowSize(int width, int height) {
        this.windowWidth = width; this.windowHeight = height;
    }

    public void setGameTick(long tick) { this.gameTick = tick; }

    public void setFogData(float r, float g, float b, float a,
                           float start, float end, float density, int type, boolean enabled) {
        fogColor[0] = r; fogColor[1] = g; fogColor[2] = b; fogColor[3] = a;
        fogStart = start; fogEnd = end; fogDensity = density;
        fogType = type; fogEnabled = enabled;
    }

    public void setChunkVisibility(int visible, int total,
                                    int opaqueDraws, int translucentDraws, boolean changed) {
        visibleSectionCount = visible; totalSectionCount = total;
        opaqueDrawCallCount = opaqueDraws; translucentDrawCallCount = translucentDraws;
        viewAreaChanged = changed;
    }

    public void setFrameIndex(int index) { this.frameIndex = index; }

    // ==================== 重置方法（每帧开始时调用） ====================

    /**
     * 重置帧数据到默认状态
     * <p>
     * 在每帧开始时调用，保留历史视图矩阵缓冲区。
     */
    public void reset() {
        identityMatrix(projectionMatrix);
        identityMatrix(viewMatrix);
        identityMatrix(viewProjectionMatrix);
        identityMatrix(invViewMatrix);

        cameraX = 0; cameraY = 0; cameraZ = 0;
        yaw = 0; pitch = 0; fov = 70.0f;
        nearPlane = 0.05f; farPlane = 1000.0f;

        windowWidth = 0; windowHeight = 0;
        gameTick = 0;

        fogColor[0] = 0; fogColor[1] = 0; fogColor[2] = 0; fogColor[3] = 1;
        fogStart = 0; fogEnd = 1000; fogDensity = 0;
        fogType = 0; fogEnabled = false;

        visibleSectionCount = 0; totalSectionCount = 0;
        opaqueDrawCallCount = 0; translucentDrawCallCount = 0;
        viewAreaChanged = false;

        frameIndex++;
    }

    // ==================== 工具方法 ====================

    /**
     * 将 4x4 矩阵设为单位矩阵
     *
     * @param m 16 floats，column-major
     */
    private static void identityMatrix(float[] m) {
        m[0] = 1;  m[4] = 0;  m[8]  = 0; m[12] = 0;
        m[1] = 0;  m[5] = 1;  m[9]  = 0; m[13] = 0;
        m[2] = 0;  m[6] = 0;  m[10] = 1; m[14] = 0;
        m[3] = 0;  m[7] = 0;  m[11] = 0; m[15] = 1;
    }

    /**
     * 4x4 矩阵乘法：result = a × b
     * <p>
     * 所有矩阵均为 column-major 布局。
     *
     * @param a 左操作数（16 floats）
     * @param b 右操作数（16 floats）
     * @param result 输出（16 floats，允许与 a 或 b 相同引用）
     */
    private static void multiplyMatrix4x4(float[] a, float[] b, float[] result) {
        float r0  = a[0]*b[0]  + a[4]*b[1]  + a[8]*b[2]   + a[12]*b[3];
        float r1  = a[1]*b[0]  + a[5]*b[1]  + a[9]*b[2]   + a[13]*b[3];
        float r2  = a[2]*b[0]  + a[6]*b[1]  + a[10]*b[2]  + a[14]*b[3];
        float r3  = a[3]*b[0]  + a[7]*b[1]  + a[11]*b[2]  + a[15]*b[3];

        float r4  = a[0]*b[4]  + a[4]*b[5]  + a[8]*b[6]   + a[12]*b[7];
        float r5  = a[1]*b[4]  + a[5]*b[5]  + a[9]*b[6]   + a[13]*b[7];
        float r6  = a[2]*b[4]  + a[6]*b[5]  + a[10]*b[6]  + a[14]*b[7];
        float r7  = a[3]*b[4]  + a[7]*b[5]  + a[11]*b[6]  + a[15]*b[7];

        float r8  = a[0]*b[8]  + a[4]*b[9]  + a[8]*b[10]  + a[12]*b[11];
        float r9  = a[1]*b[8]  + a[5]*b[9]  + a[9]*b[10]  + a[13]*b[11];
        float r10 = a[2]*b[8]  + a[6]*b[9]  + a[10]*b[10] + a[14]*b[11];
        float r11 = a[3]*b[8]  + a[7]*b[9]  + a[11]*b[10] + a[15]*b[11];

        float r12 = a[0]*b[12] + a[4]*b[13] + a[8]*b[14]  + a[12]*b[15];
        float r13 = a[1]*b[12] + a[5]*b[13] + a[9]*b[14]  + a[13]*b[15];
        float r14 = a[2]*b[12] + a[6]*b[13] + a[10]*b[14] + a[14]*b[15];
        float r15 = a[3]*b[12] + a[7]*b[13] + a[11]*b[14] + a[15]*b[15];

        result[0] = r0;  result[4] = r4;  result[8]  = r8;  result[12] = r12;
        result[1] = r1;  result[5] = r5;  result[9]  = r9;  result[13] = r13;
        result[2] = r2;  result[6] = r6;  result[10] = r10; result[14] = r14;
        result[3] = r3;  result[7] = r7;  result[11] = r11; result[15] = r15;
    }

    @Override
    public String toString() {
        return String.format(
            "FrameDataSnapshot{camera=(%.1f,%.1f,%.1f), fov=%.1f, window=%dx%d, " +
            "tick=%d, visibleSections=%d/%d, frame=%d}",
            cameraX, cameraY, cameraZ, fov,
            windowWidth, windowHeight, gameTick,
            visibleSectionCount, totalSectionCount, frameIndex
        );
    }
}
