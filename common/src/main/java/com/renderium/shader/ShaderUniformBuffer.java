// Renderium - Uniform 缓冲区
// 管理 Vulkan Uniform Buffer 的数据上传和绑定
// 使用动态偏移实现同一缓冲区服务多个着色器

package com.renderium.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Uniform 缓冲区管理器。
 *
 * <p>管理 Vulkan Uniform Buffer 的数据上传和绑定。
 * 使用动态偏移（dynamic offsets）实现同一缓冲区服务多个着色器实例，
 * 减少 VkBuffer 分配数量和绑定次数。
 *
 * <h2>架构</h2>
 * <pre>
 * UniformBuffer (VkBuffer, 64KB)
 * ├── Offset 0:    FrameData (mat4 viewProj, vec3 cameraPos, float time)
 * ├── Offset 256:  FogData (vec4 fogColor, float fogStart, float fogEnd)
 * ├── Offset 512:  LightData (vec4[16] lightPositions, vec4[16] lightColors)
 * └── ... (动态分配)
 * </pre>
 *
 * <h2>对齐规则</h2>
 * <p>Vulkan 要求 Uniform Buffer 的偏移满足 minUniformBufferOffsetAlignment 对齐。
 * 默认对齐为 256 bytes（NVIDIA/AMD 通用值）。
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public final class ShaderUniformBuffer {

    private static final Logger LOGGER = Logger.getLogger(ShaderUniformBuffer.class.getName());

    /** 默认对齐（bytes） */
    private static final int DEFAULT_ALIGNMENT = 256;

    /** 默认缓冲区大小（64 KB） */
    private static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    // ==================== Uniform 块定义 ====================

    /**
     * Uniform 块描述
     */
    public static final class UniformBlock {
        /** 块名称（对应 GLSL 中的 layout(binding=N) uniform BlockName） */
        public final String name;
        /** Binding 点 */
        public final int binding;
        /** 块大小（bytes） */
        public final int size;
        /** 缓冲区中的偏移（对齐后） */
        public int offset;
        /** 是否脏（需要更新） */
        public boolean dirty;

        UniformBlock(String name, int binding, int size) {
            this.name = name;
            this.binding = binding;
            this.size = size;
            this.offset = -1;
            this.dirty = true;
        }
    }

    // ==================== 实例字段 ====================

    /** 缓冲区总大小 */
    private final int bufferSize;

    /** 对齐要求 */
    private final int alignment;

    /** CPU 端数据缓冲区 */
    private ByteBuffer cpuBuffer;

    /** 已注册的 Uniform 块 */
    private final Map<String, UniformBlock> blocks;

    /** 块的注册顺序（决定偏移分配顺序） */
    private final List<UniformBlock> blockOrder;

    /** 当前已分配的偏移 */
    private int currentOffset;

    /** GPU 端 VkBuffer handle */
    private long gpuBufferHandle;

    /** 是否需要上传到 GPU */
    private volatile boolean needsUpload;

    // ==================== 构造 ====================

    public ShaderUniformBuffer() {
        this(DEFAULT_BUFFER_SIZE, DEFAULT_ALIGNMENT);
    }

    public ShaderUniformBuffer(int bufferSize, int alignment) {
        this.bufferSize = bufferSize;
        this.alignment = alignment;
        this.cpuBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.nativeOrder());
        this.blocks = new ConcurrentHashMap<>();
        this.blockOrder = new ArrayList<>();
        this.currentOffset = 0;
        this.gpuBufferHandle = 0L;
        this.needsUpload = false;
    }

    // ==================== 注册 API ====================

    /**
     * 注册 Uniform 块
     *
     * @param name 块名称
     * @param binding Binding 点
     * @param size 块大小（bytes）
     * @return 是否注册成功
     */
    public boolean registerBlock(String name, int binding, int size) {
        if (blocks.containsKey(name)) {
            return false; // 已存在
        }

        // 计算对齐后的偏移
        int alignedOffset = alignUp(currentOffset, alignment);
        int alignedSize = alignUp(size, alignment);

        if (alignedOffset + alignedSize > bufferSize) {
            LOGGER.warning("Uniform buffer overflow: cannot allocate " + name +
                    " (size=" + size + ", available=" + (bufferSize - alignedOffset) + ")");
            return false;
        }

        UniformBlock block = new UniformBlock(name, binding, size);
        block.offset = alignedOffset;
        blocks.put(name, block);
        blockOrder.add(block);
        currentOffset = alignedOffset + alignedSize;

        return true;
    }

    // ==================== 数据写入 API ====================

    /**
     * 写入 float 数据到指定块
     *
     * @param blockName 块名称
     * @param offsetInBlock 块内偏移（bytes）
     * @param value float 值
     */
    public void setFloat(String blockName, int offsetInBlock, float value) {
        UniformBlock block = blocks.get(blockName);
        if (block == null) return;

        cpuBuffer.putFloat(block.offset + offsetInBlock, value);
        block.dirty = true;
        needsUpload = true;
    }

    /**
     * 写入 int 数据到指定块
     */
    public void setInt(String blockName, int offsetInBlock, int value) {
        UniformBlock block = blocks.get(blockName);
        if (block == null) return;

        cpuBuffer.putInt(block.offset + offsetInBlock, value);
        block.dirty = true;
        needsUpload = true;
    }

    /**
     * 写入 vec4 (4 floats) 到指定块
     */
    public void setVec4(String blockName, int offsetInBlock,
                         float x, float y, float z, float w) {
        UniformBlock block = blocks.get(blockName);
        if (block == null) return;

        int pos = block.offset + offsetInBlock;
        cpuBuffer.putFloat(pos, x);
        cpuBuffer.putFloat(pos + 4, y);
        cpuBuffer.putFloat(pos + 8, z);
        cpuBuffer.putFloat(pos + 12, w);
        block.dirty = true;
        needsUpload = true;
    }

    /**
     * 写入 mat4 (16 floats) 到指定块
     */
    public void setMat4(String blockName, int offsetInBlock, float[] matrix) {
        if (matrix == null || matrix.length != 16) return;

        UniformBlock block = blocks.get(blockName);
        if (block == null) return;

        int pos = block.offset + offsetInBlock;
        for (int i = 0; i < 16; i++) {
            cpuBuffer.putFloat(pos + i * 4, matrix[i]);
        }
        block.dirty = true;
        needsUpload = true;
    }

    /**
     * 批量写入字节数据到指定块
     */
    public void setBytes(String blockName, int offsetInBlock, byte[] data) {
        UniformBlock block = blocks.get(blockName);
        if (block == null) return;

        System.arraycopy(data, 0, cpuBuffer.array(), block.offset + offsetInBlock, data.length);
        block.dirty = true;
        needsUpload = true;
    }

    // ==================== 上传与绑定 ====================

    /**
     * 上传脏数据到 GPU
     *
     * <p>只上传标记为 dirty 的块，减少带宽消耗
     *
     * @return 是否有数据被上传
     */
    public boolean uploadToGPU() {
        if (!needsUpload) return false;

        // TODO: 调用 vkMapMemory + memcpy 上传到 GPU
        // 只上传脏块的数据
        for (UniformBlock block : blockOrder) {
            if (block.dirty) {
                // vkMapMemory(gpuBufferHandle, block.offset, block.size)
                // memcpy(cpuBuffer, block.offset, block.size)
                block.dirty = false;
            }
        }

        needsUpload = false;
        return true;
    }

    /**
     * 标记所有块为脏（每帧开始时调用）
     */
    public void markAllDirty() {
        for (UniformBlock block : blockOrder) {
            block.dirty = true;
        }
        needsUpload = true;
    }

    // ==================== GPU 句柄 ====================

    public void setGpuBufferHandle(long handle) {
        this.gpuBufferHandle = handle;
    }

    public long getGpuBufferHandle() {
        return gpuBufferHandle;
    }

    /**
     * 获取指定块的动态偏移（用于 vkCmdBindDescriptorSets 的 dynamicOffsets）
     */
    public int getDynamicOffset(String blockName) {
        UniformBlock block = blocks.get(blockName);
        return block != null ? block.offset : 0;
    }

    // ==================== 辅助方法 ====================

    /**
     * 向上对齐
     */
    private int alignUp(int value, int alignment) {
        return (value + alignment - 1) & ~(alignment - 1);
    }

    // ==================== Getter ====================

    public int getBufferSize() { return bufferSize; }
    public int getAlignment() { return alignment; }
    public int getUsedBytes() { return currentOffset; }
    public int getBlockCount() { return blocks.size(); }
    public boolean needsUpload() { return needsUpload; }

    public String getDiagnostics() {
        return String.format(
            "ShaderUniformBuffer{size=%dKB, used=%dKB, blocks=%d, gpu=0x%X, dirty=%s}",
            bufferSize / 1024, currentOffset / 1024, blocks.size(),
            gpuBufferHandle, needsUpload);
    }
}
