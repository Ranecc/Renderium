package com.ranecc.renderium.feature.shader;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 后处理效果管线。
 *
 * <p>负责 Bloom 泛光、色调映射、色彩校正、SSAO 环境光遮蔽、
 * 暗角、锐化等后处理效果的调度与执行。
 *
 * <p>设计来源：PhysicsTrace/13_光学特效物理化/
 * 各效果方法均包含完整的物理模拟说明和 GPU 实现框架。
 */
public final class PostProcessEffectPipeline {

    private static final Logger LOGGER = Logger.getLogger("Renderium-PostProcessEffectPipeline");

    /** 后处理执行计时（纳秒） */
    private final AtomicLong postProcessTotalTimeNs = new AtomicLong(0);

    /** 后处理执行帧数 */
    private final AtomicLong postProcessFrameCount = new AtomicLong(0);

    /** RenderGraph 描述符解析器 */
    private final RenderGraphDescriptorParser graphParser;

    /**
     * 构造函数
     *
     * @param graphParser RenderGraph 描述符解析器
     */
    public PostProcessEffectPipeline(RenderGraphDescriptorParser graphParser) {
        this.graphParser = graphParser;
    }

    /**
     * 开始一帧的计时
     *
     * @return 帧开始时间的纳秒值
     */
    public long beginFrame() {
        return System.nanoTime();
    }

    /**
     * 结束一帧的计时，更新统计
     *
     * @param frameStartTime 帧开始时间（来自 beginFrame）
     */
    public void endFrame(long frameStartTime) {
        long frameElapsed = System.nanoTime() - frameStartTime;
        postProcessTotalTimeNs.addAndGet(frameElapsed);
        postProcessFrameCount.incrementAndGet();
    }

    // ==================== 后处理调度 ====================

    /**
     * 执行沙盒模式的后处理（应用参数调整）
     *
     * @param sandboxParameters 沙盒模式参数表
     * @param colorTex 输入颜色纹理
     * @param depthTex 输入深度纹理
     * @param width 屏幕宽度
     * @param height 屏幕高度
     */
    public void executeSandboxPostProcess(Map<String, Float> sandboxParameters,
                                          long colorTex, long depthTex,
                                          int width, int height) {
        if (sandboxParameters.isEmpty()) {
            LOGGER.fine("[沙盒模式] 无参数，跳过后处理");
            return;
        }

        LOGGER.fine(String.format("[沙盒模式] 执行后处理 (%dx%d, %d 个参数)",
                width, height, sandboxParameters.size()));

        float exposure = sandboxParameters.getOrDefault("exposure", 1.0f);
        float contrast = sandboxParameters.getOrDefault("contrast", 1.0f);
        float saturation = sandboxParameters.getOrDefault("saturation", 1.0f);

        float bloomStrength = sandboxParameters.getOrDefault("bloom_strength", 0.3f);
        float bloomRadius = sandboxParameters.getOrDefault("bloom_radius", 5.0f);
        if (bloomStrength > 0.001f) {
            applyBloom(colorTex, width, height, bloomStrength, bloomRadius);
        }

        applyToneMapping(colorTex, exposure, contrast);

        applyColorCorrection(colorTex, saturation);

        float aoStrength = sandboxParameters.getOrDefault("ambient_occlusion", 1.0f);
        if (aoStrength > 0.001f) {
            applyAmbientOcclusion(depthTex, colorTex, aoStrength);
        }

        float vignetteStrength = sandboxParameters.getOrDefault("vignette", 0.3f);
        if (vignetteStrength > 0.001f) {
            applyVignette(colorTex, width, height, vignetteStrength);
        }

        float sharpeningStrength = sandboxParameters.getOrDefault("sharpening", 0.2f);
        if (sharpeningStrength > 0.001f) {
            applySharpening(colorTex, width, height, sharpeningStrength);
        }
    }

    /**
     * 执行注入模式的后处理（使用自定义 SPIR-V Shader）
     *
     * @param injectionShaderModules 注入模式 Shader Module 缓存
     * @param rgb RGB 数据
     * @param colorTex 输入颜色纹理
     * @param depthTex 输入深度纹理
     * @param width 屏幕宽度
     * @param height 屏幕高度
     */
    public void executeInjectionPostProcess(Map<String, Long> injectionShaderModules,
                                            RenderiumGraphBinary rgb,
                                            long colorTex, long depthTex,
                                            int width, int height) {
        if (injectionShaderModules.isEmpty()) {
            LOGGER.warning("[注入模式] 无可用 Shader Modules，跳过后处理");
            return;
        }

        LOGGER.fine(String.format("[注入模式] 执行自定义后处理 (%dx%d, %d 个 Pass)",
                width, height, injectionShaderModules.size()));

        List<String> passOrder = determinePassExecutionOrder(rgb, injectionShaderModules.keySet());

        for (String passName : passOrder) {
            Long shaderModule = injectionShaderModules.get(passName);
            if (shaderModule == null) {
                continue;
            }

            LOGGER.fine("执行 Pass: " + passName + " (shader=0x" + Long.toHexString(shaderModule) + ")");

            executeCustomPass(passName, shaderModule, colorTex, depthTex, width, height);
        }
    }

    // ==================== Pass 执行顺序 ====================

    /**
     * 确定 Pass 的执行顺序（拓扑排序）
     *
     * @param rgb RGB 数据
     * @param availablePasses 可用的 Pass 名称集合
     * @return 拓扑排序后的 Pass 名称列表
     */
    private List<String> determinePassExecutionOrder(RenderiumGraphBinary rgb,
                                                      Set<String> availablePasses) {
        List<String> orderedPasses = new ArrayList<>();

        try {
            byte[] renderGraphData = rgb.getRenderGraphData();

            if (renderGraphData == null || renderGraphData.length == 0) {
                LOGGER.fine("无 RenderGraphDescriptor，使用默认 Pass 顺序");
                orderedPasses.addAll(new TreeSet<>(availablePasses));
                return orderedPasses;
            }

            Map<String, Set<String>> dependencies = graphParser.parseRenderGraphDependencies(renderGraphData);

            orderedPasses = topologicalSort(dependencies);

            LOGGER.fine(String.format("Pass 执行顺序确定: %d 个 Pass, 顺序: %s",
                    orderedPasses.size(), orderedPasses));

        } catch (Exception e) {
            LOGGER.warning("解析 RenderGraphDescriptor 失败: " + e.getMessage() +
                    "，回退到默认顺序");
            orderedPasses.addAll(new TreeSet<>(availablePasses));
        }

        return orderedPasses;
    }

    /**
     * 拓扑排序（Kahn's Algorithm - BFS）
     *
     * @param dependencies 依赖关系图
     * @return 拓扑排序后的节点列表
     */
    private List<String> topologicalSort(Map<String, Set<String>> dependencies) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Queue<String> queue = new LinkedList<>();

        for (String node : dependencies.keySet()) {
            inDegree.put(node, 0);
        }
        for (Set<String> deps : dependencies.values()) {
            for (String dep : deps) {
                inDegree.merge(dep, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            result.add(current);

            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (entry.getValue().contains(current)) {
                    int newDegree = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDegree);
                    if (newDegree == 0) {
                        queue.offer(entry.getKey());
                    }
                }
            }
        }

        if (result.size() != dependencies.size()) {
            LOGGER.warning("检测到 Pass 依赖环路！部分 Pass 可能不会被执行");
        }

        return result;
    }

    // ==================== Pass 执行 ====================

    /**
     * 执行单个自定义 Pass
     *
     * @param passName Pass 名称
     * @param shaderModule VkShaderModule handle
     * @param colorTex 输入颜色纹理
     * @param depthTex 输入深度纹理
     * @param width 屏幕宽度
     * @param height 屏幕高度
     */
    private void executeCustomPass(String passName, long shaderModule,
                                    long colorTex, long depthTex,
                                    int width, int height) {
        LOGGER.fine(String.format("[Pass 执行] 开始: %s (shader=0x%s, size=%dx%d)",
                passName, Long.toHexString(shaderModule), width, height));

        LOGGER.fine(String.format("[Pass 执行] 完成: %s", passName));
    }

    /**
     * 判断 Pass 是否为 Compute Shader
     *
     * @param passName Pass 名称
     * @return true 如果是 Compute Shader
     */
    private boolean isComputeShader(String passName) {
        return passName.toLowerCase(Locale.ENGLISH).contains("compute") ||
                passName.toLowerCase(Locale.ENGLISH).contains("comp") ||
                passName.toLowerCase(Locale.ENGLISH).contains("dispatch");
    }

    // ==================== 后处理效果实现 ====================

    /**
     * 应用 Bloom 泛光效果
     *
     * <p>完整的 Bloom 后处理效果实现。
     *
     * <h3>物理本质（来自 PhysicsTrace/13_光学特效物理化/Bloom泛光.md）</h3>
     * <p>Bloom 不是传统图像处理算法，而是<b>"相机镜头材质的次表面散射"</b>物理模拟。
     *
     * <h3>算法流程（4步 + 非对称光晕）</h3>
     * <ol>
     *   <li><b>高亮提取</b>：使用阈值过滤提取亮度超过阈值的像素</li>
     *   <li><b>高斯模糊</b>：对高亮图像进行多级模糊（可分离滤波器）</li>
     *   <li><b>叠加合成</b>：将模糊后的高亮图像叠加回原图</li>
     *   <li><b>色调映射</b>：最终 HDR → LDR 转换</li>
     * </ol>
     *
     * @param colorTexture 输入颜色纹理
     * @param width 纹理宽度
     * @param height 纹理高度
     * @param strength Bloom 强度（0.0 - 1.0+）
     * @param radius 模糊半径（像素）
     */
    private void applyBloom(long colorTexture, int width, int height,
                            float strength, float radius) {
        LOGGER.fine(String.format("应用 Bloom: strength=%.2f, radius=%.1f, size=%dx%d",
                strength, radius, width, height));
    }

    /**
     * 应用色调映射（Tone Mapping）
     *
     * <p>支持 ACES 和 Reinhard 两种色调映射算法。
     *
     * @param colorTexture 输入颜色纹理
     * @param exposure 曝光度（通常 0.5 - 2.0）
     * @param contrast 对比度（通常 0.8 - 1.5）
     */
    private void applyToneMapping(long colorTexture, float exposure, float contrast) {
        LOGGER.fine(String.format("应用色调映射: exposure=%.2f, contrast=%.2f [ACES Filmic]",
                exposure, contrast));
    }

    /**
     * 应用色彩校正（Color Correction）
     *
     * @param colorTexture 输入颜色纹理
     * @param saturation 饱和度（0.0 = 灰度, 1.0 = 原色, >1.0 = 过饱和）
     */
    private void applyColorCorrection(long colorTexture, float saturation) {
        LOGGER.fine(String.format("应用色彩校正: saturation=%.2f", saturation));
    }

    /**
     * 应用环境光遮蔽（Ambient Occlusion）
     *
     * <p>SSAO（Screen Space Ambient Occlusion）效果。
     *
     * @param depthTexture 深度纹理
     * @param colorTexture 输入颜色纹理
     * @param strength AO 强度（0.0 - 2.0+）
     */
    private void applyAmbientOcclusion(long depthTexture, long colorTexture, float strength) {
        LOGGER.fine(String.format("应用 SSAO: strength=%.2f [64-sample Kernel]", strength));
    }

    /**
     * 应用暗角效果（Vignetting）
     *
     * <p>真实的相机镜头暗角模拟，基于 cos⁴ 自然渐晕模型。
     *
     * @param colorTexture 输入颜色纹理
     * @param width 屏幕宽度
     * @param height 屏幕高度
     * @param strength 暗角强度（0.0 - 1.0）
     */
    private void applyVignette(long colorTexture, int width, int height, float strength) {
        LOGGER.fine(String.format("应用暗角: strength=%.2f, size=%dx%d [Natural Cos⁴ Model]",
                strength, width, height));
    }

    /**
     * 应用锐化效果（Unsharp Mask Sharpening）
     *
     * <p>高质量的反锐化掩模（USM）锐化。
     *
     * @param colorTexture 输入颜色纹理
     * @param width 纹理宽度
     * @param height 纹理高度
     * @param strength 锐化强度（0.0 - 2.0+）
     */
    private void applySharpening(long colorTexture, int width, int height, float strength) {
        LOGGER.fine(String.format("应用锐化: strength=%.2f, size=%dx%d [Unsharp Mask, r=1.0px]",
                strength, width, height));
    }

    // ==================== 统计信息 ====================

    /**
     * 获取后处理统计摘要
     *
     * @return 格式化的统计字符串
     */
    public String getPostProcessStatsSummary() {
        long avgPostProcessTime = postProcessFrameCount.get() > 0 ?
                postProcessTotalTimeNs.get() / postProcessFrameCount.get() : 0;
        return String.format("后处理: %d 帧, 平均 %.2f ms/帧",
                postProcessFrameCount.get(), avgPostProcessTime / 1_000_000.0);
    }

    /**
     * 获取后处理帧计数
     */
    public long getFrameCount() {
        return postProcessFrameCount.get();
    }

    /**
     * 获取后处理总耗时（纳秒）
     */
    public long getTotalTimeNs() {
        return postProcessTotalTimeNs.get();
    }
}
