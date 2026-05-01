// Renderium - 可见性编码
// 区段遮挡剔除的核心数据结构：将 6x6 方向可见性矩阵编码为 64-bit long
// 算法思路参考 CaffeineMC Sodium (LGPL-3.0) 的 VisibilityEncoding，独立重写

package com.ranecc.renderium.feature.culling.optimization.optimization.culling;

/**
 * 可见性编码工具类。
 *
 * <p>将一个区段的 6×6 方向可见性矩阵（从方向 A 能否看到方向 B）
 * 编码为一个 64-bit long 值，实现 O(1) 的连接查询。
 *
 * <h2>编码格式</h2>
 * <p>6 个方向 × 6 个方向 = 36 个方向对，每个方向对占 1 bit。
 * 加上每个方向的自身位（6 bit），共 42 bit，但实际只使用 36 bit。
 *
 * <pre>
 * bit 索引 = from * 8 + to  （使用 8 而非 6，利用 SWAR 优化）
 *
 * 方向定义：
 *   0 = DOWN,  1 = UP,  2 = NORTH
 *   3 = SOUTH, 4 = WEST, 5 = EAST
 *
 * 示例：如果从 DOWN 方向进入可以看到 UP 方向出去
 *   bit = 0 * 8 + 1 = 1
 *   visibilityData |= (1L &lt;&lt; 1)
 * </pre>
 *
 * <h2>SWAR 优化原理</h2>
 * <p>使用 {@code from * 8 + to} 而非 {@code from * 6 + to} 的原因：
 * <ul>
 *   <li>8 是 2 的幂，乘法可以用左移替代（{@code from << 3 | to}）</li>
 *   <li>每行 8 bit 对齐，便于用位掩码一次性提取某方向的出方向</li>
 *   <li>createMask() 利用 SWAR（SIMD Within A Register）一次扩展 6 bit 到 48 bit</li>
 * </ul>
 *
 * <h2>foldOutgoingDirections 原理</h2>
 * <p>将 6 行（每行 8 bit）的出方向"折叠"为一个 6 bit 的方向掩码：
 * <ol>
 *   <li>{@code folded |= folded >> 32}：折叠高 32 位到低 32 位</li>
 *   <li>{@code folded |= folded >> 16}：折叠高 16 位到低 16 位</li>
 *   <li>{@code folded |= folded >> 8}：折叠高 8 位到低 8 位</li>
 *   <li>低 6 bit 即为合并后的出方向掩码</li>
 * </ol>
 *
 * @see RenderiumSectionOcclusionCuller
 * @see <a href="https://github.com/CaffeineMC/sodium/blob/dev/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/occlusion/VisibilityEncoding.java">Sodium VisibilityEncoding</a>
 * @author Renderium Team
 * @since 1.0.0
 */
public final class VisibilityEncoding {

    /** 空可见性数据（区段不存在或未构建） */
    public static final long NULL = 0L;

    /** 方向数量 */
    private static final int DIRECTION_COUNT = 6;

    /** 所有方向掩码（6 bit） */
    private static final int ALL_DIRECTIONS = (1 << DIRECTION_COUNT) - 1;

    /**
     * 私有构造函数 - 工具类不可实例化
     */
    private VisibilityEncoding() {
        throw new AssertionError("工具类不可实例化");
    }

    // ==================== 编码接口 ====================

    /**
     * 计算方向对的 bit 索引
     *
     * <p>使用 {@code from * 8 + to} 编码（SWAR 优化对齐），
     * 而非 {@code from * 6 + to}（紧凑但无法用位运算加速）。
     *
     * @param from 入方向 [0, 5]
     * @param to   出方向 [0, 5]
     * @return bit 索引 [0, 47]
     */
    public static int bit(int from, int to) {
        return (from << 3) | to;
    }

    /**
     * 设置方向对的可见性
     *
     * @param visibilityData 当前可见性数据
     * @param from 入方向
     * @param to   出方向
     * @return 更新后的可见性数据
     */
    public static long setConnection(long visibilityData, int from, int to) {
        return visibilityData | (1L << bit(from, to));
    }

    /**
     * 查询方向对的可见性
     *
     * @param visibilityData 可见性数据
     * @param from 入方向
     * @param to   出方向
     * @return true 如果从 from 方向可以看到 to 方向
     */
    public static boolean isConnected(long visibilityData, int from, int to) {
        return (visibilityData & (1L << bit(from, to))) != 0L;
    }

    // ==================== 连接查询 ====================

    /**
     * 根据入方向集合，计算所有可能的出方向
     *
     * <p>这是遮挡剔除 BFS 遍历中最核心的查询。
     * 给定当前区段的可见性数据和入方向掩码，
     * 返回所有可以到达的出方向掩码。
     *
     * <h3>算法步骤</h3>
     * <ol>
     *   <li>根据入方向掩码创建行选择掩码（createMask）</li>
     *   <li>与可见性数据做 AND，只保留入方向对应的行</li>
     *   <li>折叠所有行，合并出方向（foldOutgoingDirections）</li>
     * </ol>
     *
     * @param visibilityData 区段的可见性编码
     * @param incomingDirections 入方向掩码（哪些方向有路径进入此区段）
     * @return 出方向掩码（可以从哪些方向离开此区段）
     */
    public static int getConnections(long visibilityData, int incomingDirections) {
        return foldOutgoingDirections(visibilityData & createMask(incomingDirections));
    }

    /**
     * 计算所有可能的出方向（不考虑入方向限制）
     *
     * <p>用于起点区段（没有入方向限制）或禁用遮挡剔除时。
     *
     * @param visibilityData 区段的可见性编码
     * @return 所有可能的出方向掩码
     */
    public static int getConnections(long visibilityData) {
        return foldOutgoingDirections(visibilityData);
    }

    // ==================== SWAR 内部实现 ====================

    /**
     * 创建行选择掩码
     *
     * <p>将入方向掩码（6 bit）扩展为 48 bit 的行选择掩码。
     * 每个入方向对应 8 bit 的行，如果该方向存在则行全为 1，否则全为 0。
     *
     * <h3>SWAR 扩展原理</h3>
     * <pre>
     * 输入: incoming = 0b00100101 (方向 0, 2, 5 存在)
     *
     * Step 1: 重复模式
     *   0b0000001_0000001_0000001_0000001_0000001_0000001L * incoming
     *   = 将每个 bit 扩展到对应字节
     *
     * Step 2: 掩码对齐
     *   & 0b00000001_00000001_00000001_00000001_00000001_00000001L
     *   = 只保留每个字节的最低 bit
     *
     * Step 3: 字节填充
     *   * 0xFF
     *   = 将每个 1 bit 扩展为 0xFF（8 个 1）
     * </pre>
     *
     * @param incoming 入方向掩码
     * @return 行选择掩码（48 bit，每 8 bit 一组）
     */
    static long createMask(int incoming) {
        long expanded = 0b0000001_0000001_0000001_0000001_0000001_0000001L
                * Integer.toUnsignedLong(incoming);
        return (expanded & 0b00000001_00000001_00000001_00000001_00000001_00000001L) * 0xFFL;
    }

    /**
     * 折叠出方向
     *
     * <p>将 6 行（每行 8 bit，低 6 bit 有效）的出方向合并为一个 6 bit 掩码。
     * 使用 SWAR 折叠技术，3 次位运算完成。
     *
     * <h3>折叠原理</h3>
     * <pre>
     * 输入（48 bit，6 行 × 8 bit）:
     *   Row 5: [bit47..bit40] → 低 6 bit 是方向 5 的出方向
     *   Row 4: [bit39..bit32] → 低 6 bit 是方向 4 的出方向
     *   ...
     *   Row 0: [bit7..bit0]   → 低 6 bit 是方向 0 的出方向
     *
     * Step 1: folded |= folded >> 32
     *   将 Row 5-4 折叠到 Row 1-0
     *
     * Step 2: folded |= folded >> 16
     *   将 Row 3-2 折叠到 Row 1-0
     *
     * Step 3: folded |= folded >> 8
     *   将 Row 1 折叠到 Row 0
     *
     * 结果: Row 0 的低 6 bit = 所有行的 OR 合并
     * </pre>
     *
     * @param data 经过行选择掩码过滤后的可见性数据
     * @return 合并后的出方向掩码（6 bit）
     */
    static int foldOutgoingDirections(long data) {
        long folded = data;
        folded |= folded >> 32;
        folded |= folded >> 16;
        folded |= folded >> 8;
        return (int) (folded & ALL_DIRECTIONS);
    }
}
