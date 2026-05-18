/**
 * 区块生命周期管理子系统 (Chunk Manager Subsystem)。
 *
 * <p>包含区块渲染段管理器与玩家运动预测器（卡尔曼滤波器）。
 *
 * <h2>核心类</h2>
 * <ul>
 *   <li>{@link com.ranecc.renderium.feature.chunk.manager.RenderSectionManager} — PACELC分层+爆炸防涌入</li>
 *   <li>{@link com.ranecc.renderium.feature.chunk.manager.PlayerMotionPredictor} — 6维卡尔曼位置预测</li>
 * </ul>
 *
 * @see com.ranecc.renderium.feature.chunk.build.ChunkBuildPipeline
 */
package com.ranecc.renderium.feature.chunk.manager;
