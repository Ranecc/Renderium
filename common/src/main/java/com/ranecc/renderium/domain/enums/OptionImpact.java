// Renderium - 选项性能影响级别枚举
// 用于标注选项对渲染性能的影响程度

package com.ranecc.renderium.domain.enums;

/**
 * 选项性能影响级别。
 *
 * <p>用于向用户直观展示修改某个选项对游戏性能的预期影响，
 * 帮助用户在画面质量和帧率之间做出权衡决策。
 *
 * <h2>影响级别说明</h2>
 * <table border="1">
 *   <tr><th>级别</th><th>描述</th><th>典型选项</th></tr>
 *   <tr><td>LOW</td><td>对性能几乎无影响</td><td>界面语言、提示框显示</td></tr>
 *   <tr><td>MEDIUM</td><td>轻微影响帧率</td><td>粒子数量、云朵渲染</td></tr>
 *   <tr><td>HIGH</td><td>显著影响帧率</td><td>渲染距离、阴影质量</td></tr>
 *   <tr><td>VERY_HIGH</td><td>极大影响，需谨慎调整</td><td>光线追踪、路径追踪</td></tr>
 *   <tr><td>VARIABLE</td><td>取决于场景复杂度</td><td>LOD 级别、动态分辨率</td></tr>
 * </table>
 *
 * <h2>UI 展示建议</h2>
 * <ul>
 *   <li>LOW - 使用灰色或绿色图标</li>
 *   <li>MEDIUM - 使用黄色警告图标</li>
 *   <li>HIGH - 使用橙色图标</li>
 *   <li>VERY_HIGH / VARIABLE - 使用红色图标并附加文字说明</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererOption#getImpact()
 */
public enum OptionImpact {

    /**
     * 低影响（< 1% 帧时间变化）。
     * <p>此类选项通常只涉及 UI 或简单的状态切换，
     * 对 GPU/CPU 负载基本无影响。
     */
    LOW,

    /**
     * 中等影响（1-5% 帧时间变化）。
     * <p>可能会在某些场景下导致轻微掉帧，
     * 但大多数硬件配置可以忽略不计。
     */
    MEDIUM,

    /**
     * 高影响（5-15% 帧时间变化）。
     * <p>会明显影响帧率，用户在调整时应关注 FPS 变化。
     * 建议在中低端硬件上保持默认或较低设置。
     */
    HIGH,

    /**
     * 极高影响（> 15% 帧时间变化，可能翻倍）。
     * <p>此类选项通常涉及昂贵的渲染技术（如光线追踪），
     * 仅推荐在高端硬件上启用。UI 应给出明确警告。
     */
    VERY_HIGH,

    /**
     * 可变影响（取决于场景复杂度和当前负载）。
     * <p>某些选项在不同场景下影响差异巨大：
     * 在简单场景中影响极低，但在复杂场景中可能导致严重卡顿。
     * UI 应显示"视情况而定"之类的提示。
     */
    VARIABLE
}
