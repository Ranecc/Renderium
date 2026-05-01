// Renderium - 选项特性标记枚举
// 用于标记选项的特殊属性和行为约束

package com.ranecc.renderium.domain.enums;

import java.util.EnumSet;

/**
 * 选项特性标记。
 *
 * <p>用于为选项添加元数据标记，控制系统行为和 UI 展示方式。
 * 一个选项可以拥有多个标记（使用 {@link EnumSet} 存储）。
 *
 * <h2>可用标记</h2>
 * <table border="1">
 *   <tr><th>标记</th><th>用途</th><th>示例选项</th></tr>
 *   <tr>
 *     <td>{@link #REQUIRES_RENDERER_RELOAD}</td>
 *     <td>修改后需要重新加载渲染器（轻量级）</td>
 *     <td>渲染距离、画质预设、实体剔除</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #REQUIRES_GAME_RESTART}</td>
 *     <td>修改后需要重启游戏才能生效</td>
 *     <td>帧生成模式、内存分配模式</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #REQUIRES_VIDEOMODE_RELOAD}</td>
 *     <td>修改后需要重新初始化视频模式</td>
 *     <td>全屏模式、分辨率、GUI 缩放</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #REQUIRES_ASSET_RELOAD}</td>
 *     <td>修改后需要重新加载资源包</td>
 *     <td>Mipmap 等级、纹理过滤模式</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #REQUIRES_RENDERER_UPDATE}</td>
 *     <td>修改后需要更新渲染状态（可热重载）</td>
 *     <td>区块构建延迟、动画可见纹理</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #EXPERIMENTAL}</td>
 *     <td>实验性功能，可能不稳定</td>
 *     <td>实验性 Shader 特性</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #ADVANCED}</td>
 *     <td>高级选项，仅在专家模式下显示</td>
 *     <td>手动 LOD 参数调节</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #HARDWARE_DEPENDENT}</td>
 *     <td>依赖特定硬件支持</td>
 *     <td>DLSS/FSR、Mesh Shading</td>
 *   </tr>
 * </table>
 *
 * @author Renderium Team
 * @since 5.3.0
 * @see RendererOption#getFlags()
 */
public enum OptionFlag {

    /**
     * 需要重新加载渲染器（轻量级操作）。
     * <p>标记此标志的选项在值变更后，
     * 渲染器会在下一帧自动应用更改，无需重启或重载资源。
     * 典型场景：渲染距离调整、画质等级切换、实体剔除开关等。
     */
    REQUIRES_RENDERER_RELOAD,

    /**
     * 需要重启游戏才能生效。
     * <p>标记此标志的选项在值变更后，
     * UI 应显示警告信息，提示用户需要重启 Minecraft 才能使更改生效。
     * 典型场景：底层渲染器切换、内存分配策略变更、帧生成启用等。
     */
    REQUIRES_GAME_RESTART,

    /**
     * 需要重新初始化视频模式。
     * <p>标记此标志的选项在值变更后，
     * 窗口系统会重新配置显示模式（可能伴随短暂黑屏）。
     * 典型场景：全屏模式切换、分辨率变更、刷新率调整等。
     */
    REQUIRES_VIDEOMODE_RELOAD,

    /**
     * 需要重新加载资源包。
     * <p>标记此标志的选项在值变更后，
     * 资源管理器会重新加载相关纹理和模型数据。
     * 典型场景：Mipmap 等级调整、纹理过滤模式切换、着色器更换等。
     */
    REQUIRES_ASSET_RELOAD,

    /**
     * 需要更新渲染器状态（可热重载）。
     * <p>标记此标志的选项在值变更后，
     * 渲染管线会更新内部状态但无需完全重建。
     * 这是比 REQUIRES_RENDERER_RELOAD 更轻量的操作。
     * 典型场景：区块构建延迟策略、动画纹理可见性开关等。
     */
    REQUIRES_RENDERER_UPDATE,

    /**
     * 实验性功能标记。
     * <p>表示该选项对应的功能仍在开发或测试阶段，
     * 可能存在稳定性问题、性能问题或兼容性问题。
     * UI 应显示明显的实验性标识（如 ⚠️ 图标）。
     */
    EXPERIMENTAL,

    /**
     * 高级选项标记。
     * <p>标记此标志的选项仅在"高级模式"或"开发者模式"下显示，
     * 普通用户界面中隐藏以降低认知负担。
     * 适用于需要专业知识的深度调优选项。
     */
    ADVANCED,

    /**
     * 硬件依赖标记。
     * <p>表示该选项的功能依赖于特定的硬件能力（如 GPU 特性、驱动版本）。
     * 系统应在运行时检测硬件兼容性，
     * 不满足条件时自动禁用该选项并显示原因。
     * 示例：Ray Tracing、Variable Rate Shading 等。
     */
    HARDWARE_DEPENDENT;
}
