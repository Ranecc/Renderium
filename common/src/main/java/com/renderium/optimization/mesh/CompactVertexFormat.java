// Renderium - 紧凑顶点格式
// 使用 SoA (Structure of Arrays) 布局替代 AoS (Array of Structures)
// 借鉴 Sodium 的紧凑顶点编码，减少显存占用和带宽消耗

package com.renderium.optimization.mesh;

/**
 * 紧凑顶点格式。
 *
 * <p>借鉴了 Sodium 的紧凑顶点编码策略，使用 SoA（Structure of Arrays）
 * 布局替代传统的 AoS（Array of Structures）布局。
 *
 * <h2>传统 AoS 布局（原版 Minecraft）</h2>
 * <pre>
 * Vertex = [x, y, z, u, v, r, g, b, a, nx, ny, nz, light]  // 36+ bytes
 * [v0: x y z u v r g b a nx ny nz light]
 * [v1: x y z u v r g b a nx ny nz light]
 * [v2: x y z u v r g b a nx ny nz light]
 * </pre>
 *
 * <h2>SoA 布局（Renderium）</h2>
 * <pre>
 * Position Stream: [x0 y0 z0] [x1 y1 z1] [x2 y2 z2] ...  // 12 bytes/vertex
 * UV Stream:       [u0 v0]     [u1 v1]     [u2 v2]     ...  // 4-8 bytes/vertex
 * Color Stream:    [c0]        [c1]        [c2]        ...  // 4 bytes/vertex
 * Normal Stream:   [n0]        [n1]        [n2]        ...  // 4 bytes/vertex
 * Light Stream:    [l0]        [l1]        [l2]        ...  // 2 bytes/vertex
 * </pre>
 *
 * <h2>性能优势</h2>
 * <ul>
 *   <li><b>显存节省</b>：Minecraft 的顶点格式有大量冗余字段，
 *       紧凑编码后每个顶点从 36+ bytes 降至 22-26 bytes</li>
 *   <li><b>带宽节省</b>：GPU 只需读取需要的流，不需要的属性不会浪费带宽</li>
 *   <li><b>缓存友好</b>：连续的相同属性数据提高缓存命中率</li>
 *   <li><b>量化编码</b>：法线用 1 byte 存储（8:8:8 signed normalized），
 *       光照用 2 bytes 存储（压缩的 combined light）</li>
 * </ul>
 *
 * <h2>与 Sodium 的对比</h2>
 * <p>Sodium 使用自定义的 {@code QuadVertexType} 编码，
 * 将位置、纹理、颜色、光照打包到 28 bytes/vertex。
 * Renderium 使用 SoA 布局，允许 GPU 独立访问每个属性流，
 * 更适合 Vulkan 的 binding 机制。
 *
 * @see <a href="https://github.com/CaffeineMC/sodium">Sodium Vertex Encoding</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class CompactVertexFormat {

    // ==================== 顶点属性流定义 ====================

    /**
     * 顶点属性流类型
     */
    public enum StreamType {
        /** 位置流：3 × float = 12 bytes */
        POSITION(12),
        /** 纹理坐标流：2 × float = 8 bytes，或 2 × short = 4 bytes */
        TEX_COORD(8),
        /** 颜色流：4 × byte (ABGR packed) = 4 bytes */
        COLOR(4),
        /** 法线流：1 × int (packed 10-10-10-2) = 4 bytes */
        NORMAL(4),
        /** 光照流：2 × short (combined light) = 2 bytes */
        LIGHT(2);

        /** 每个顶点在此流中的字节数 */
        public final int bytesPerVertex;

        StreamType(int bytesPerVertex) {
            this.bytesPerVertex = bytesPerVertex;
        }
    }

    // ==================== 格式配置 ====================

    /** 是否包含位置流（总是包含） */
    private final boolean hasPosition;

    /** 是否包含纹理坐标流 */
    private final boolean hasTexCoord;

    /** 是否包含颜色流 */
    private final boolean hasColor;

    /** 是否包含法线流 */
    private final boolean hasNormal;

    /** 是否包含光照流 */
    private final boolean hasLight;

    /** 是否使用压缩纹理坐标（short 替代 float） */
    private final boolean compressedTexCoord;

    /** 每个顶点的总字节数 */
    private final int stride;

    /** 活跃的流类型列表 */
    private final StreamType[] activeStreams;

    // ==================== 预定义格式 ====================

    /** 区块网格格式（最常用） */
    public static final CompactVertexFormat CHUNK_MESH = new Builder()
            .withPosition()
            .withTexCoord(true)   // 压缩纹理坐标
            .withColor()
            .withNormal()
            .withLight()
            .build();

    /** 粒子格式 */
    public static final CompactVertexFormat PARTICLE = new Builder()
            .withPosition()
            .withTexCoord(false)  // float 纹理坐标
            .withColor()
            .build();

    /** 线框格式 */
    public static final CompactVertexFormat LINE = new Builder()
            .withPosition()
            .withColor()
            .build();

    /** GUI 格式 */
    public static final CompactVertexFormat GUI = new Builder()
            .withPosition()
            .withTexCoord(false)
            .withColor()
            .build();

    // ==================== 构造函数 ====================

    private CompactVertexFormat(boolean hasPosition, boolean hasTexCoord,
                                boolean hasColor, boolean hasNormal,
                                boolean hasLight, boolean compressedTexCoord) {
        this.hasPosition = hasPosition;
        this.hasTexCoord = hasTexCoord;
        this.hasColor = hasColor;
        this.hasNormal = hasNormal;
        this.hasLight = hasLight;
        this.compressedTexCoord = compressedTexCoord;

        // 计算步长
        int s = 0;
        java.util.List<StreamType> streams = new java.util.ArrayList<>();
        if (hasPosition) { s += StreamType.POSITION.bytesPerVertex; streams.add(StreamType.POSITION); }
        if (hasTexCoord) {
            s += compressedTexCoord ? 4 : StreamType.TEX_COORD.bytesPerVertex;
            streams.add(StreamType.TEX_COORD);
        }
        if (hasColor) { s += StreamType.COLOR.bytesPerVertex; streams.add(StreamType.COLOR); }
        if (hasNormal) { s += StreamType.NORMAL.bytesPerVertex; streams.add(StreamType.NORMAL); }
        if (hasLight) { s += StreamType.LIGHT.bytesPerVertex; streams.add(StreamType.LIGHT); }

        this.stride = s;
        this.activeStreams = streams.toArray(new StreamType[0]);
    }

    // ==================== 编码方法 ====================

    /**
     * 将法线向量打包为 32 位整数
     *
     * <p>使用 10-10-10-2 格式：
     * <pre>
     * bits 0-9:  X 分量 (signed normalized)
     * bits 10-19: Y 分量 (signed normalized)
     * bits 20-29: Z 分量 (signed normalized)
     * bits 30-31: 保留
     * </pre>
     *
     * @param nx X 分量 (-1.0 ~ 1.0)
     * @param ny Y 分量 (-1.0 ~ 1.0)
     * @param nz Z 分量 (-1.0 ~ 1.0)
     * @return 打包后的 32 位整数
     */
    public static int packNormal(float nx, float ny, float nz) {
        int ix = (int) (nx * 511.0f) & 0x3FF;
        int iy = (int) (ny * 511.0f) & 0x3FF;
        int iz = (int) (nz * 511.0f) & 0x3FF;
        return ix | (iy << 10) | (iz << 20);
    }

    /**
     * 从打包的法线中解包 X 分量
     */
    public static float unpackNormalX(int packed) {
        return ((packed & 0x3FF) - 512) / 511.0f;
    }

    /**
     * 从打包的法线中解包 Y 分量
     */
    public static float unpackNormalY(int packed) {
        return (((packed >> 10) & 0x3FF) - 512) / 511.0f;
    }

    /**
     * 从打包的法线中解包 Z 分量
     */
    public static float unpackNormalZ(int packed) {
        return (((packed >> 20) & 0x3FF) - 512) / 511.0f;
    }

    /**
     * 将颜色打包为 ABGR 格式的 32 位整数
     *
     * @param r 红色 (0.0 ~ 1.0)
     * @param g 绿色 (0.0 ~ 1.0)
     * @param b 蓝色 (0.0 ~ 1.0)
     * @param a 透明度 (0.0 ~ 1.0)
     * @return ABGR 打包的 32 位整数
     */
    public static int packColor(float r, float g, float b, float a) {
        int ir = (int) (r * 255.0f) & 0xFF;
        int ig = (int) (g * 255.0f) & 0xFF;
        int ib = (int) (b * 255.0f) & 0xFF;
        int ia = (int) (a * 255.0f) & 0xFF;
        return ia << 24 | ib << 16 | ig << 8 | ir;
    }

    /**
     * 将 Combined Light 打包为 16 位整数
     *
     * <p>Minecraft 的 Combined Light = (Block Light << 4) | (Sky Light << 20)
     * 压缩为 2 bytes：高 8 位 = Sky Light / 16，低 8 位 = Block Light / 16
     *
     * @param combinedLight 原始 Combined Light 值
     * @return 压缩后的 16 位值
     */
    public static short packLight(int combinedLight) {
        int blockLight = (combinedLight >> 4) & 0xF;
        int skyLight = (combinedLight >> 20) & 0xF;
        return (short) ((skyLight << 4) | blockLight);
    }

    /**
     * 解包 Combined Light
     *
     * @param packed 压缩的 16 位值
     * @return 原始 Combined Light 值
     */
    public static int unpackLight(short packed) {
        int blockLight = packed & 0xF;
        int skyLight = (packed >> 4) & 0xF;
        return (skyLight << 20) | (blockLight << 4);
    }

    /**
     * 将纹理坐标压缩为 16 位定点数
     *
     * @param u U 坐标 (0.0 ~ 1.0)
     * @param v V 坐标 (0.0 ~ 1.0)
     * @return 打包的 32 位值（高 16 位 = V，低 16 位 = U）
     */
    public static int packTexCoord(float u, float v) {
        int iu = (int) (u * 65535.0f) & 0xFFFF;
        int iv = (int) (v * 65535.0f) & 0xFFFF;
        return (iv << 16) | iu;
    }

    // ==================== Getter ====================

    public boolean hasPosition() { return hasPosition; }
    public boolean hasTexCoord() { return hasTexCoord; }
    public boolean hasColor() { return hasColor; }
    public boolean hasNormal() { return hasNormal; }
    public boolean hasLight() { return hasLight; }
    public boolean isCompressedTexCoord() { return compressedTexCoord; }
    public int getStride() { return stride; }
    public StreamType[] getActiveStreams() { return activeStreams.clone(); }

    /**
     * 计算指定顶点数量所需的缓冲区大小
     *
     * @param vertexCount 顶点数量
     * @return 字节数
     */
    public int calculateBufferSize(int vertexCount) {
        return stride * vertexCount;
    }

    /**
     * 计算指定流在 SoA 布局中的偏移量
     *
     * @param streamType 流类型
     * @param vertexCount 总顶点数量
     * @return 该流在 SoA 缓冲区中的字节偏移量
     */
    public int getStreamOffset(StreamType streamType, int vertexCount) {
        int offset = 0;
        for (StreamType s : activeStreams) {
            if (s == streamType) return offset;
            offset += getStreamBytes(s, vertexCount);
        }
        return -1;
    }

    /**
     * 获取指定流的字节大小
     */
    public int getStreamBytes(StreamType streamType, int vertexCount) {
        int bytesPerVertex = switch (streamType) {
            case POSITION -> StreamType.POSITION.bytesPerVertex;
            case TEX_COORD -> compressedTexCoord ? 4 : StreamType.TEX_COORD.bytesPerVertex;
            case COLOR -> StreamType.COLOR.bytesPerVertex;
            case NORMAL -> StreamType.NORMAL.bytesPerVertex;
            case LIGHT -> StreamType.LIGHT.bytesPerVertex;
        };
        return bytesPerVertex * vertexCount;
    }

    /**
     * 计算 SoA 布局的总缓冲区大小
     *
     * @param vertexCount 顶点数量
     * @return SoA 布局的总字节数
     */
    public int calculateSoABufferSize(int vertexCount) {
        int total = 0;
        for (StreamType s : activeStreams) {
            total += getStreamBytes(s, vertexCount);
        }
        return total;
    }

    @Override
    public String toString() {
        return String.format("CompactVertexFormat{stride=%d, pos=%s, uv=%s, color=%s, normal=%s, light=%s, compressedUV=%s}",
                stride, hasPosition, hasTexCoord, hasColor, hasNormal, hasLight, compressedTexCoord);
    }

    // ==================== Builder ====================

    /**
     * CompactVertexFormat 的 Builder
     */
    public static final class Builder {
        private boolean hasPosition = false;
        private boolean hasTexCoord = false;
        private boolean hasColor = false;
        private boolean hasNormal = false;
        private boolean hasLight = false;
        private boolean compressedTexCoord = false;

        public Builder withPosition() { this.hasPosition = true; return this; }
        public Builder withTexCoord(boolean compressed) { this.hasTexCoord = true; this.compressedTexCoord = compressed; return this; }
        public Builder withColor() { this.hasColor = true; return this; }
        public Builder withNormal() { this.hasNormal = true; return this; }
        public Builder withLight() { this.hasLight = true; return this; }

        public CompactVertexFormat build() {
            if (!hasPosition) {
                throw new IllegalStateException("Position stream is required");
            }
            return new CompactVertexFormat(hasPosition, hasTexCoord, hasColor,
                    hasNormal, hasLight, compressedTexCoord);
        }
    }
}
