// Renderium - 紧凑方块存储
// 狂暴模式下替代 PalettedContainer 的高效存储方案
// 使用纯 int ID 操作，不直接依赖 MC 类

package com.renderium.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Renderium 紧凑方块存储实现。
 *
 * <p>算法思路参考了 CaffeineMC 的 Sodium 项目 (LGPL-3.0) 中的数据结构设计。
 * 本代码为 Renderium 团队完全独立重写，用于狂暴模式下替代原版 PalettedContainer。
 *
 * <h2>设计目标</h2>
 * <p>原版 Minecraft 使用 {@code PalettedContainer<BlockState>} 存储区块数据，
 * 该容器存在以下问题：
 * <ul>
 *   <li>大量对象引用，GC 压力高</li>
 *   <li>动态调色板扩容，内存碎片化</li>
 *   <li>不支持零拷贝访问，无法直接喂给 GPU</li>
 * </ul>
 *
 * <p>本存储使用 Panama FFM 的 MemorySegment 实现堆外 SoA 存储：
 * <ul>
 *   <li><b>固定大小</b>：16x16x16 = 4096 个方块，预分配固定内存</li>
 *   <li><b>紧凑编码</b>：每个方块用 16-bit 存储 block state ID</li>
 *   <li><b>堆外存储</b>：完全脱离 JVM 堆，GC 压力归零</li>
 *   <li><b>直接映射</b>：MemorySegment 可直接传给 C++/Vulkan</li>
 * </ul>
 *
 * <p><b>重要</b>：本类不直接引用 Minecraft 的 Block/BlockState 类。
 * 所有操作使用 int 类型的 BlockState ID，由平台模块负责 ID 与对象的转换。
 *
 * @see <a href="https://github.com/CaffeineMC/sodium">Sodium Repository</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class RenderiumCompactStorage {

    public static final int SECTION_SIZE = 16;
    public static final int BLOCK_COUNT = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;

    private static final int BYTES_PER_BLOCK_STATE = 2;
    private static final int BYTES_PER_LIGHT = 2;

    public static final int TOTAL_BLOCK_DATA_SIZE = BLOCK_COUNT * BYTES_PER_BLOCK_STATE;
    public static final int TOTAL_LIGHT_DATA_SIZE = BLOCK_COUNT * BYTES_PER_LIGHT;

    /** 方块状态 ID 数组（堆外）- 16-bit 无符号整数 */
    private final MemorySegment blockStateIds;

    /** 光照数据数组（堆外）- 16-bit packed */
    private final MemorySegment lightData;

    /** 内存管理 Arena */
    private final Arena arena;

    /** 脏标记 */
    private volatile boolean dirty = false;

    /** 区段原点坐标 */
    private final int originX, originY, originZ;

    /**
     * 创建紧凑存储实例
     *
     * @param arena   内存 Arena（由调用者管理生命周期）
     * @param originX 区块原点 X
     * @param originY 区块原点 Y
     * @param originZ 区块原点 Z
     */
    public RenderiumCompactStorage(Arena arena, int originX, int originY, int originZ) {
        this.arena = arena;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;

        this.blockStateIds = arena.allocate(TOTAL_BLOCK_DATA_SIZE);
        this.lightData = arena.allocate(TOTAL_LIGHT_DATA_SIZE);

        this.blockStateIds.fill((byte) 0);
        this.lightData.fill((byte) 0);
    }

    // ==================== 核心读写接口（使用 int BlockState ID）====================

    /**
     * 获取指定位置的方块状态 ID
     *
     * @param x 局部 X [0, 15]
     * @param y 局部 Y [0, 15]
     * @param z 局部 Z [0, 15]
     * @return 方块状态 ID（16-bit 无符号）
     */
    public int getBlockStateId(int x, int y, int z) {
        validateCoords(x, y, z);
        int index = computeIndex(x, y, z);
        return blockStateIds.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * BYTES_PER_BLOCK_STATE) & 0xFFFF;
    }

    /**
     * 设置指定位置的方块状态 ID
     *
     * @param x       局部 X [0, 15]
     * @param y       局部 Y [0, 15]
     * @param z       局部 Z [0, 15]
     * @param stateId 方块状态 ID（16-bit 无符号）
     * @return 之前的方块状态 ID
     */
    public int setBlockStateId(int x, int y, int z, int stateId) {
        validateCoords(x, y, z);
        int index = computeIndex(x, y, z);

        int previous = getBlockStateId(x, y, z);
        blockStateIds.set(ValueLayout.JAVA_SHORT_UNALIGNED, index * BYTES_PER_BLOCK_STATE, (short) (stateId & 0xFFFF));
        this.dirty = true;

        return previous;
    }

    /**
     * 批量填充整个存储（用于初始化）
     *
     * @param stateIds 4096 个方块状态 ID 的数组
     */
    public void fillByIds(int[] stateIds) {
        if (stateIds.length != BLOCK_COUNT) {
            throw new IllegalArgumentException("需要恰好 " + BLOCK_COUNT + " 个方块状态 ID");
        }

        for (int i = 0; i < BLOCK_COUNT; i++) {
            blockStateIds.set(ValueLayout.JAVA_SHORT_UNALIGNED, i * BYTES_PER_BLOCK_STATE, (short) (stateIds[i] & 0xFFFF));
        }
        this.dirty = true;
    }

    // ==================== 光照数据 ====================

    /**
     * 获取指定位置的光照数据
     *
     * @return packed 格式：(blockLight) | (skyLight << 8)
     */
    public int getLight(int x, int y, int z) {
        validateCoords(x, y, z);
        int index = computeIndex(x, y, z);
        return lightData.get(ValueLayout.JAVA_SHORT_UNALIGNED, index * BYTES_PER_LIGHT) & 0xFFFF;
    }

    /**
     * 设置指定位置的光照数据
     *
     * @param light packed 格式：(blockLight) | (skyLight << 8)
     */
    public void setLight(int x, int y, int z, int light) {
        validateCoords(x, y, z);
        int index = computeIndex(x, y, z);
        lightData.set(ValueLayout.JAVA_SHORT_UNALIGNED, index * BYTES_PER_LIGHT, (short) (light & 0xFFFF));
    }

    // ==================== 直接内存访问 ====================

    public MemorySegment getBlockStateRawData() { return blockStateIds; }
    public MemorySegment getLightRawData() { return lightData; }
    public Arena getArena() { return arena; }

    // ==================== 状态查询 ====================

    public boolean isDirty() { return dirty; }
    public void clearDirty() { this.dirty = false; }
    public int getOriginX() { return originX; }
    public int getOriginY() { return originY; }
    public int getOriginZ() { return originZ; }
    public long getTotalAllocatedBytes() { return TOTAL_BLOCK_DATA_SIZE + TOTAL_LIGHT_DATA_SIZE; }

    // ==================== 内部工具 ====================

    public static int computeIndex(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int indexToX(int index) { return index & 0xF; }
    public static int indexToY(int index) { return (index >> 8) & 0xF; }
    public static int indexToZ(int index) { return (index >> 4) & 0xF; }

    private static void validateCoords(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SECTION_SIZE || y >= SECTION_SIZE || z >= SECTION_SIZE) {
            throw new IndexOutOfBoundsException(
                    String.format("坐标超出范围: (%d, %d, %d), 允许范围 [0, %d]", x, y, z, SECTION_SIZE - 1));
        }
    }

    @Override
    public String toString() {
        return String.format("RenderiumCompactStorage{origin=(%d,%d,%d), dirty=%s, memory=%dB}",
                originX, originY, originZ, dirty, getTotalAllocatedBytes());
    }
}
