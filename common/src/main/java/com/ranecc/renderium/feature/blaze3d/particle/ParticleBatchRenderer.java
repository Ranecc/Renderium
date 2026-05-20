package com.ranecc.renderium.feature.blaze3d.particle;

import java.util.*;
import java.util.logging.Logger;

/**
 * 粒子批量渲染器 — 将大量粒子合并为极少的 Instanced Draw Call
 * <p>
 * Minecraft 粒子系统默认每个粒子一个 Draw Call，在大量粒子场景（Botania 魔力发射、
 * 烟花、爆炸等）下造成严重性能瓶颈。本渲染器将相同纹理的粒子合并为单个 Instanced Draw。
 * <p>
 * 性能预期：
 * <ul>
 *   <li>1000 粒子：1000 Draw Call → 1-5 Instanced Draw Call（200x 减少）</li>
 *   <li>5000 粒子：5000 Draw Call → 5-10 Instanced Draw Call（500-1000x 减少）</li>
 *   <li>CPU 开销：< 0.1ms/帧（批量构建 + 矩阵打包）</li>
 * </ul>
 *
 * <h3>数据布局：</h3>
 * <pre>
 * 每个粒子实例数据（48 bytes）：
 * ┌──────────────────────────────────────────────────┐
 * │ transformMatrix[0..15]  (64B) — 4x4 仿射变换矩阵 │
 * │ color[0..3]             (16B) — RGBA 浮点颜色     │
 * │ uvOffset[0..3]          (16B) — 纹理坐标偏移+大小  │
 * └──────────────────────────────────────────────────┘
 * </pre>
 *
 * @since 5.5.0
 */
public class ParticleBatchRenderer {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ParticleBatch");

    // ==================== 配置 ====================

    /** 最大粒子数 */
    private static final int MAX_PARTICLES = 65536;

    /** 每粒子实例数据大小（4x4矩阵 + RGBA + UV = 96 bytes） */
    private static final int INSTANCE_DATA_SIZE = 96;

    // ==================== 粒子数据 ====================

    /**
     * 粒子数据 DTO
     */
    public static class ParticleData {
        public float x, y, z;           // 世界坐标
        public float prevX, prevY, prevZ; // 上一帧坐标（用于插值）
        public float scale;             // 缩放
        public float r, g, b, a;        // RGBA 颜色
        public float u0, v0, u1, v1;    // 纹理坐标
        public int textureIndex;        // Bindless 纹理索引
        public boolean alive;           // 是否存活

        public ParticleData() {
            this.alive = true;
            this.scale = 1.0f;
            this.a = 1.0f;
        }

        /**
         * 获取 4x4 变换矩阵（行主序）
         * <p>
         * 包含平移、缩放和 Billboard 旋转（面向相机）
         */
        public float[] getTransformMatrix() {
            float[] m = new float[16];
            // 简化的 Billboard 矩阵：仅平移 + 缩放
            // 实际渲染时由 Vertex Shader 根据 cameraPosition 计算 Billboard 旋转
            m[0] = scale;  m[5] = scale;  m[10] = scale;  m[15] = 1.0f;
            m[12] = x;     m[13] = y;     m[14] = z;
            return m;
        }
    }

    // ==================== 批次管理 ====================

    /**
     * 批次键：纹理索引
     * <p>
     * 粒子按纹理分组合并，相同纹理的粒子共享一个 Instanced Draw Call
     */
    private static final record BatchKey(int textureIndex) {}

    /** 当前帧的粒子批次 */
    private final Map<BatchKey, List<ParticleData>> batches = new HashMap<>();

    /** 实例数据打包缓冲区（每帧复用，避免 GC） */
    private float[] instanceDataBuffer = new float[MAX_PARTICLES * INSTANCE_DATA_SIZE / 4];

    /** 当前帧粒子总数 */
    private int particleCount = 0;

    /** 当前帧批次数 */
    private int batchCount = 0;

    // ==================== 粒子池（对象复用） ====================

    /** 粒子对象池，避免每帧大量 new ParticleData() */
    private final Deque<ParticleData> pool = new ArrayDeque<>();

    /**
     * 从池中获取粒子对象
     */
    public ParticleData acquireParticle() {
        ParticleData p = pool.pollFirst();
        if (p == null) {
            p = new ParticleData();
        } else {
            // 重置为默认值
            p.alive = true;
            p.scale = 1.0f;
            p.a = 1.0f;
            p.r = p.g = p.b = 1.0f;
            p.u0 = p.v0 = 0.0f;
            p.u1 = p.v1 = 1.0f;
            p.textureIndex = 0;
        }
        return p;
    }

    /**
     * 归还粒子对象到池
     */
    public void releaseParticle(ParticleData p) {
        if (pool.size() < MAX_PARTICLES) {
            pool.addLast(p);
        }
    }

    // ==================== 核心渲染方法 ====================

    /**
     * 构建渲染批次
     * <p>
     * 将存活粒子按纹理索引分组，每组对应一个 Instanced Draw Call。
     *
     * @param particles 当前帧所有存活粒子
     */
    public void buildBatches(List<ParticleData> particles) {
        batches.clear();
        particleCount = 0;

        for (ParticleData p : particles) {
            if (!p.alive) continue;

            BatchKey key = new BatchKey(p.textureIndex);
            batches.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            particleCount++;

            // 动态扩展实例数据缓冲区
            if (particleCount * INSTANCE_DATA_SIZE / 4 >= instanceDataBuffer.length) {
                instanceDataBuffer = new float[instanceDataBuffer.length * 2];
            }
        }

        batchCount = batches.size();
    }

    /**
     * 将所有批次的实例数据打包到连续缓冲区
     * <p>
     * 输出格式：每个粒子 96 bytes（24 floats）
     * - float[0..15]: 4x4 变换矩阵
     * - float[16..19]: RGBA 颜色
     * - float[20..23]: UV 偏移和大小 (u0, v0, u1, v1)
     *
     * @return 打包的 float 数组
     */
    public float[] packInstanceData() {
        int pos = 0;
        for (var entry : batches.entrySet()) {
            for (ParticleData p : entry.getValue()) {
                float[] matrix = p.getTransformMatrix();
                System.arraycopy(matrix, 0, instanceDataBuffer, pos, 16);
                pos += 16;

                // RGBA
                instanceDataBuffer[pos++] = p.r;
                instanceDataBuffer[pos++] = p.g;
                instanceDataBuffer[pos++] = p.b;
                instanceDataBuffer[pos++] = p.a;

                // UV
                instanceDataBuffer[pos++] = p.u0;
                instanceDataBuffer[pos++] = p.v0;
                instanceDataBuffer[pos++] = p.u1;
                instanceDataBuffer[pos++] = p.v1;
            }
        }
        return instanceDataBuffer;
    }

    /**
     * 获取打包数据的字节大小
     */
    public int getPackedDataSizeBytes() {
        return particleCount * INSTANCE_DATA_SIZE;
    }

    // ==================== 统计 ====================

    /**
     * 获取批次数（= Instanced Draw Call 数量）
     */
    public int getBatchCount() {
        return batchCount;
    }

    /**
     * 获取当前帧粒子总数
     */
    public int getParticleCount() {
        return particleCount;
    }

    /**
     * 获取合并率（0.0~1.0，1.0 = 完美合并）
     * <p>
     * 合并率 = 1 - (批次数 / 粒子数)
     * 1000 粒子 5 个批次 → 合并率 99.5%
     */
    public float getMergeRate() {
        if (particleCount <= 1) return 1.0f;
        return 1.0f - (float) batchCount / particleCount;
    }

    /**
     * 获取对象池大小
     */
    public int getPoolSize() {
        return pool.size();
    }

    // ==================== 帧生命周期 ====================

    /**
     * 每帧开始时调用，重置批次状态
     */
    public void beginFrame() {
        batches.clear();
        particleCount = 0;
        batchCount = 0;
    }

    /**
     * 每帧结束时调用，回收死亡粒子到对象池
     * <p>
     * 注意：此方法会修改传入的粒子列表，移除死亡粒子
     *
     * @param particles 当前帧粒子列表
     */
    public void endFrame(List<ParticleData> particles) {
        particles.removeIf(p -> {
            if (!p.alive) {
                releaseParticle(p);
                return true;
            }
            return false;
        });
    }
}
