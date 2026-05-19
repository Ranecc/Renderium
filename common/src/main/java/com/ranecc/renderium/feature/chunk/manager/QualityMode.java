// Renderium - 速度感知质量档位
// 高速移动时粗层兜底，静止时细层享受——人眼在120°/s下本来就没有解析精细阴影的能力

package com.ranecc.renderium.domain.model;

/**
 * 速度感知渲染质量档位。
 *
 * <p>两条铁律：
 * <ol>
 *   <li><b>高速时粗层不是"偷工减料"，而是"认知匹配"</b><br>
 *       人眼在 120°/s 甩鼠标时本来就没有解析精细阴影的能力，硬算是生理浪费</li>
 *   <li><b>粗层是细层的中间产物，不是额外内存</b><br>
 *       COARSE 直接拿 Stage 0/1 结果渲染，不停推到 Stage 2，零额外内存</li>
 * </ol>
 *
 * <h2>触发阈值（滞回设计）</h2>
 * <pre>
 * 进入 COARSE:  ω ≥ 60°/s  OR  v ≥ 10 m/s  OR  kalman误差 > 0.5块
 * 退出 COARSE:  ω < 30°/s  AND v < 6 m/s  AND  kalman误差 < 0.2块
 *               连续 500ms 不触发 coarse 才恢复（防止闪烁）
 * </pre>
 *
 * <h2>工程细节</h2>
 * <ul>
 *   <li><b>停下来跳变</b>: 退出 COARSE 后不立即全量精炼，沿用渐进管线放宽 deadline 到 6 帧</li>
 *   <li><b>内存双缓冲</b>: COARSE 就是细层的 Stage 0/1，提前终止即可，不额外分配</li>
 *   <li><b>截图兜底</b>: 连续 500ms 静止+无输入，后台自动推 Stage 2</li>
 * </ul>
 */
public enum QualityMode {
    /** 粗层兜底——仅在高速时使用。几何只用 greedy meshing，不推 AO，不压缩 */
    COARSE,

    /** 正常渐进精炼——静止/低速享受 */
    FINE;

    /** @return 该模式下允许的最高精炼阶段 */
    public int maxAllowedStage() {
        return this == COARSE ? 1 : 2; // COARSE cap at Stage 1, FINE allows Stage 2
    }

    /** @return COARSE 模式下退出后，精炼 deadline 放宽到几帧 */
    public int recoveryGraceFrames() {
        return this == COARSE ? 6 : 3; // COARSE 退出后用 6 帧慢慢爬，正常 3 帧
    }
}
