package com.ranecc.renderium.feature.renderopt.texture;

/**
 * 纹理优化 TODO 清单
 * <p>
 * 高分辨率纹理优化支持状态与规划。
 * 所有优化遵循"不启用就短路"原则。
 *
 * @since 5.5.0
 */
public final class TextureOptimizationTODO {

    private TextureOptimizationTODO() {}

    // ==================== 已实现的纹理优化 ====================

    /**
     * ✅ Bindless Texture Table
     * <p>
     * MaterialMergedRenderer 使用 BindlessTextureTable 按材质合并 chunk，
     * 避免逐纹理切换 DescriptorSet。
     * 限制：需要 VK_EXT_descriptor_indexing 或 VK_KHR_maintenance3
     */

    /**
     * ✅ SPIR-V 着色器缓存
     * <p>
     * ShaderPipelineOptimizer 缓存编译后的 SPIR-V，
     * 避免每次启动重新编译着色器。
     */

    // ==================== TODO: 高分辨率纹理优化 ====================

    /**
     * TODO P0: 纹理流式加载 (Texture Streaming)
     * <p>
     * Unreal: Texture Streaming | Unity: Mipmap Streaming
     * <p>
     * 根据物体到相机距离动态加载/卸载 mipmap 层级：
     * - 近处：全分辨率 mipmap
     * - 远处：低分辨率 mipmap
     * - 不可见：不加载
     * <p>
     * 预估 VRAM 节省：30-50%（大型整合包 256x~1024x 纹理）
     * 预估 CPU 开销：< 0.1ms/帧（mipmap 选择计算）
     * 短路条件：textureStreamingEnabled == false
     */
    public static final String TEXTURE_STREAMING = "TODO_P0_TextureStreaming";

    /**
     * TODO P0: 纹理压缩 (BC/ASTC)
     * <p>
     * 当前纹理格式未压缩（R8G8B8A8_UNORM = 4 bytes/texel）
     * <p>
     * 压缩格式选择：
     * - PC: BC7 (0.5-1 byte/texel, 高质量) / BC3 (1 byte/texel, 有透明)
     * - 移动端: ASTC 4x4 (1 byte/texel) / ASTC 6x6 (0.56 byte/texel)
     * <p>
     * 预估 VRAM 节省：75-87.5%（4x~8x 压缩比）
     * 预估 GPU 开销：0（硬件解码，零额外开销）
     * 短路条件：textureCompressionEnabled == false
     */
    public static final String TEXTURE_COMPRESSION = "TODO_P0_TextureCompression";

    /**
     * TODO P1: 虚拟纹理 (Virtual Texture / VT)
     * <p>
     * Unreal: Virtual Texturing | Unity: Virtual Texturing
     * <p>
     * 将超大纹理（8K+）切分为 128x128 tile，按需加载到 GPU。
     * 适用于高分辨率光影包和模组纹理。
     * <p>
     * 预估 VRAM 节省：80-95%（仅加载可见 tile）
     * 预估 CPU 开销：0.5-1ms/帧（tile 反馈解析 + 页面换入）
     * 短路条件：virtualTextureEnabled == false
     */
    public static final String VIRTUAL_TEXTURE = "TODO_P1_VirtualTexture";

    /**
     * TODO P1: 各向异性过滤优化 (Anisotropic Filtering)
     * <p>
     * 当前 MC 原版各向异性过滤由驱动控制，Renderium 未主动设置。
     * <p>
     * 优化方案：
     * - 使用 VK_EXT_sampler_filter_minmax 设置最大各向异性
     * - 根据距离动态调整各向异性级别（近16x，远4x）
     * <p>
     * 预估 GPU 开销：0-5%（硬件实现，几乎零开销）
     * 短路条件：anisotropicLevel == 1
     */
    public static final String ANISOTROPIC_OPTIMIZATION = "TODO_P1_AnisotropicOptimization";

    /**
     * TODO P2: 纹理图集合并 (Texture Atlas Merging)
     * <p>
     * 将多个小纹理合并到图集，减少 DescriptorSet 切换。
     * MC 原版已有纹理图集（terrain.png），但模组纹理可能分散。
     * <p>
     * 预估 Draw Call 减少：20-30%
     * 短路条件：atlasMergingEnabled == false
     */
    public static final String TEXTURE_ATLAS_MERGING = "TODO_P2_TextureAtlasMerging";
}
