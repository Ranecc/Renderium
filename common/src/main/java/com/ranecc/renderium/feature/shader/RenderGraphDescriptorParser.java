package com.ranecc.renderium.feature.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * RenderGraphDescriptor 二进制格式解析器。
 *
 * <p>负责解析 .rgb (Renderium Graph Binary) 文件中
 * 各种二进制数据区域的格式解析，包括：
 * <ul>
 *   <li>RenderGraphDescriptor：Pass 拓扑与依赖关系</li>
 *   <li>ParameterTable：参数表（参数名 → 参数值）</li>
 *   <li>ShaderTable：SPIR-V 数据表</li>
 * </ul>
 *
 * <p>所有解析方法均为无状态工具方法，线程安全。
 */
public final class RenderGraphDescriptorParser {

    private static final Logger LOGGER = Logger.getLogger("Renderium-RenderGraphDescriptorParser");

    /**
     * 解析 RenderGraphDescriptor 中的 Pass 依赖关系
     *
     * <p>二进制格式说明：
     * <pre>
     * uint32_t passCount
     * 对于每个 pass:
     *   uint32_t nameLength
     *   char[nameLength] name
     *   uint32_t dependencyCount
     *   dependencyCount × { uint32_t depNameLength, char[depNameLength] depName }
     * </pre>
     *
     * @param renderGraphData RenderGraphDescriptor 字节数据
     * @return Pass 名称 → 依赖集合的映射
     */
    public Map<String, Set<String>> parseRenderGraphDependencies(byte[] renderGraphData) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();

        ByteBuffer buffer = ByteBuffer.wrap(renderGraphData).order(ByteOrder.LITTLE_ENDIAN);

        try {
            int passCount = buffer.getInt();

            for (int i = 0; i < passCount; i++) {
                int nameLength = buffer.getInt();
                byte[] nameBytes = new byte[nameLength];
                buffer.get(nameBytes);
                String passName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

                int depCount = buffer.getInt();
                Set<String> deps = new LinkedHashSet<>();

                for (int j = 0; j < depCount; j++) {
                    int depNameLength = buffer.getInt();
                    byte[] depNameBytes = new byte[depNameLength];
                    buffer.get(depNameBytes);
                    String depName = new String(depNameBytes, java.nio.charset.StandardCharsets.UTF_8);
                    deps.add(depName);
                }

                dependencies.put(passName, deps);
            }
        } catch (Exception e) {
            LOGGER.warning("解析 Pass 依赖关系失败: " + e.getMessage());
        }

        return dependencies;
    }

    /**
     * 从 RenderGraphDescriptor 字节数据中解析 Pass 名称
     *
     * @param renderGraphData RenderGraphDescriptor 原始数据
     * @return Pass 名称集合
     */
    public Set<String> parsePassNamesFromRenderGraph(byte[] renderGraphData) {
        Set<String> passes = new HashSet<>();

        try {
            ByteBuffer buffer = ByteBuffer.wrap(renderGraphData).order(ByteOrder.LITTLE_ENDIAN);

            int passCount = buffer.getInt();

            for (int i = 0; i < passCount; i++) {
                int nameLength = buffer.getInt();
                if (nameLength <= 0 || nameLength > 256) {
                    LOGGER.warning("无效的 Pass 名称长度: " + nameLength + ", 停止解析");
                    break;
                }

                byte[] nameBytes = new byte[nameLength];
                buffer.get(nameBytes);
                String passName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                passes.add(passName);
            }

            LOGGER.fine(String.format("从 RenderGraphDescriptor 解析了 %d 个 Pass 名称", passes.size()));

        } catch (Exception e) {
            LOGGER.warning("解析 RenderGraphDescriptor 失败: " + e.getMessage());
        }

        return passes;
    }

    /**
     * 收集光影包已声明的 Pass 名称
     *
     * <p>从 RGB 的 RenderGraphDescriptor 区域和 ShaderTable 区域
     * 真实读取 Pass 名称，而非使用硬编码的模拟数据。
     *
     * @param rgb 光影包数据
     * @return 已声明的 Pass 名称集合
     */
    public Set<String> collectDeclaredPasses(RenderiumGraphBinary rgb) {
        Set<String> passes = new HashSet<>();

        try {
            byte[] renderGraphData = rgb.getRenderGraphData();

            Iterable<String> shaderNames = rgb.getShaderNames();
            if (shaderNames != null) {
                for (String shaderName : shaderNames) {
                    passes.add(shaderName);
                }
            }

            if (renderGraphData != null && renderGraphData.length > 0) {
                Set<String> graphPasses = parsePassNamesFromRenderGraph(renderGraphData);
                passes.addAll(graphPasses);
            }

            if (passes.isEmpty() && rgb.getPassCount() > 0) {
                LOGGER.fine("RenderGraphDescriptor 为空但有 Pass 计数，使用默认 Pass 名称推断");
                passes.addAll(inferDefaultPasses(rgb));
            }

            LOGGER.fine(String.format("收集到 %d 个声明的 Pass: %s",
                    passes.size(), passes));

        } catch (Exception e) {
            LOGGER.severe("提取 RGB Pass 名称失败: " + e.getMessage());
        }

        return passes;
    }

    /**
     * 根据权限等级和 RGB 元数据推断默认 Pass 名称
     *
     * <p>当 RenderGraphDescriptor 不可用时，使用此方法推断可能的 Pass。
     * 这是一种降级策略，确保即使在不完整的光影包中也能正常工作。
     *
     * @param rgb RGB 数据
     * @return 推断的 Pass 名称集合
     */
    public Set<String> inferDefaultPasses(RenderiumGraphBinary rgb) {
        Set<String> passes = new HashSet<>();

        short flags = rgb.getFlags();

        if ((flags & RenderiumGraphBinary.FLAG_HAS_CUSTOM_SHADERS) != 0) {
            passes.add("geometry");
            passes.add("lighting");
        }

        if ((flags & RenderiumGraphBinary.FLAG_INJECTION_MODE) != 0) {
            passes.add("post_process");
            passes.add("composite");
        }

        if ((flags & RenderiumGraphBinary.FLAG_TAKEOVER_MODE) != 0) {
            passes.add("shadow_map");
            passes.add("deferred_lighting");
            passes.add("post_process");
            passes.add("composite");
        }

        passes.add("post_process");

        LOGGER.fine(String.format("推断默认 Pass: %s (flags=0x%04X)", passes, flags));

        return passes;
    }

    /**
     * 从 RGB 参数表提取参数（名 → 浮点值映射）
     *
     * <p>二进制格式说明（ParameterTable 区域）：
     * <pre>
     * Offset  Size  Field
     * 0       4     uint32_t paramCount (参数数量)
     * 4       ...   对于每个参数:
     *           4     uint32_t nameLength (参数名长度)
     *           N     char[nameLength] name (UTF-8 编码的参数名)
     *           4     float value (参数值)
     * </pre>
     *
     * @param rgb RenderiumGraphBinary 实例
     * @return 参数映射表（参数名 → 参数值），如果无参数则返回空 map
     */
    public Map<String, Float> extractParametersFromRGB(RenderiumGraphBinary rgb) {
        Map<String, Float> params = new ConcurrentHashMap<>();

        try {
            byte[] paramTableData = rgb.getParamTableData();

            if (paramTableData == null || paramTableData.length == 0) {
                LOGGER.fine("RGB 文件不包含参数表（ParameterTable 为空）");
                return params;
            }

            Map<String, Float> parsedParams = rgb.getParameterTable();

            if (parsedParams != null && !parsedParams.isEmpty()) {
                params.putAll(parsedParams);
                LOGGER.info(String.format("✓ 从 RGB 参数表提取了 %d 个参数", params.size()));
            } else {
                LOGGER.warning("RGB 参数表解析结果为空（可能是格式错误）");
            }

        } catch (Exception e) {
            LOGGER.severe("提取 RGB 参数表失败: " + e.getMessage());
        }

        return params;
    }

    /**
     * 从 RGB Shader 表提取 SPIR-V 数据
     *
     * <p>二进制格式说明（ShaderTable 区域）：
     * <pre>
     * Offset  Size  Field
     * 0       4     uint32_t shaderCount (Shader 数量)
     * 4       ...   对于每个 Shader:
     *           4     uint32_t nameLength (Pass 名称长度)
     *           N     char[nameLength] name (UTF-8 编码的 Pass 名称)
     *           4     uint32_t spirvSize (SPIR-V 数据大小)
     *           M     byte[spirvSize] spirvData (SPIR-V 字节码)
     * </pre>
     *
     * @param rgb RenderiumGraphBinary 实例
     * @return Pass 名称到 SPIR-V 字节数组的映射
     */
    public Map<String, byte[]> extractShaderTableFromRGB(RenderiumGraphBinary rgb) {
        Map<String, byte[]> shaders = new ConcurrentHashMap<>();

        try {
            Iterable<String> shaderNames = rgb.getShaderNames();
            if (shaderNames != null) {
                for (String passName : shaderNames) {
                    byte[] spirvData = rgb.getSpirvForPass(passName);
                    if (spirvData != null && spirvData.length > 0) {
                        shaders.put(passName, spirvData);
                        LOGGER.fine(String.format("提取 Shader: %s (%d bytes)", passName, spirvData.length));
                    }
                }
            }

            if (!shaders.isEmpty()) {
                LOGGER.info(String.format("✓ 从 RGB Shader 表提取了 %d 个 Shader", shaders.size()));
            } else {
                LOGGER.fine("RGB 文件的 Shader 表为空（可能仅包含参数调整）");
            }

        } catch (Exception e) {
            LOGGER.severe("提取 RGB Shader 表失败: " + e.getMessage());
        }

        return shaders;
    }
}
