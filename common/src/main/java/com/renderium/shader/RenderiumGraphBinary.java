// Renderium - .rgb 光影包格式定义和加载器
// Renderium Graph Binary 格式规范

package com.renderium.shader;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Renderium Graph Binary (.rgb) 文件格式。
 *
 * <p>.rgb 是 Renderium 专用的光影包二进制格式，用于打包：
 * <ul>
 *   <li><b>SPIR-V Shader 字节码</b>：Compute/Vertex/Fragment 着色器</li>
 *   <li><b>Render Graph 描述符</b>：Pass 依赖关系、资源绑定</li>
 *   <li><b>参数表</b>：可调节的渲染参数</li>
 *   <li><b>Lua 字节码</b>：可编程的初始化/更新逻辑</li>
 * </ul>
 *
 * <h2>文件结构</h2>
 * <pre>
 * ┌──────────────────────────────┐
 * │ Header (64 bytes)            │ ← Magic, Version, Flags
 * ├──────────────────────────────┤
 * │ RenderGraphDescriptor        │ ← Pass 列表，资源流图
 * ├──────────────────────────────┤
 * │ ParameterTable               │ ← 参数名→值映射
 * ├──────────────────────────────┤
 * │ ShaderTable                  │ ← Pass名 → SPIR-V 数据
 * ├──────────────────────────────┤
 * │ LuaBytecode (可选)           │ ← 可执行脚本
 * └──────────────────────────────┘
 * </pre>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
public class RenderiumGraphBinary {

    private static final Logger LOGGER = Logger.getLogger("Renderium-RGB");

    // ==================== 常量定义 ====================

    /** Magic Number: "RGB\0" (0x42524700) */
    public static final int MAGIC = 0x42524700;

    /** 当前格式版本 */
    public static final short VERSION = 1;

    /** Header 大小（字节） */
    public static final int HEADER_SIZE = 64;

    // ==================== Header 结构 ====================

    /**
     * RGB 文件头
     */
    public static class Header {
        /** Magic Number */
        public int magic;
        
        /** 格式版本 */
        public short version;
        
        /** 标志位 */
        public short flags;
        
        /** RenderGraphDescriptor 偏移量 */
        public int renderGraphOffset;
        
        /** RenderGraphDescriptor 大小 */
        public int renderGraphSize;
        
        /** ParameterTable 偏移量 */
        public int paramTableOffset;
        
        /** ParameterTable 大小 */
        public int paramTableSize;
        
        /** ShaderTable 偏移量 */
        public int shaderTableOffset;
        
        /** ShaderTable 大小 */
        public int shaderTableSize;
        
        /** LuaBytecode 偏移量（0 表示不存在） */
        public int luaBytecodeOffset;
        
        /** LuaBytecode 大小 */
        public int luaBytecodeSize;

        /**
         * 检查 Header 是否有效
         */
        public boolean isValid() {
            return magic == MAGIC && version >= VERSION;
        }

        /**
         * 是否包含 Lua 字节码
         */
        public boolean hasLuaBytecode() {
            return luaBytecodeOffset != 0 && luaBytecodeSize > 0;
        }
    }

    // ==================== 标志位定义 ====================

    /** 包含自定义 SPIR-V Shader */
    public static final short FLAG_HAS_CUSTOM_SHADERS = 0x0001;

    /** 使用注入模式（替换特定 Pass） */
    public static final short FLAG_INJECTION_MODE = 0x0002;

    /** 使用夺舍模式（完全接管渲染管线） */
    public static final short FLAG_TAKEOVER_MODE = 0x0004;

    /** 包含 Lua 脚本 */
    public static final short FLAG_HAS_LUA_SCRIPT = 0x0008;

    /** 加密的内容（需要解密密钥） */
    public static final short FLAG_ENCRYPTED = 0x0010;

    // ==================== 实例字段 ====================

    private final Header header;
    
    /** 渲染图描述符数据 */
    private final byte[] renderGraphData;
    
    /** 参数表数据 */
    private final byte[] paramTableData;
    
    /** Shader 表（Pass 名称 → SPIR-V 字节码） */
    private final Map<String, byte[]> shaderTable;
    
    /** Lua 字节码（可选） */
    private final byte[] luaBytecode;
    
    /** 解析后的参数缓存 */
    private Map<String, Float> parsedParameters;

    // ==================== 构造函数 ====================

    /**
     * 从文件加载 .rgb 光影包
     *
     * @param rgbFile .rgb 文件路径
     * @return RenderiumGraphBinary 实例，失败返回 null
     */
    public static RenderiumGraphBinary loadFromFile(Path rgbFile) {
        if (!Files.exists(rgbFile)) {
            LOGGER.severe(".rgb 文件不存在: " + rgbFile);
            return null;
        }

        try {
            byte[] fileData = Files.readAllBytes(rgbFile);
            return parseFromBytes(fileData);
        } catch (IOException e) {
            LOGGER.severe("读取 .rgb 文件失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从字节数组解析 .rgb 数据
     *
     * @param data .rgb 文件的完整内容
     * @return RenderiumGraphBinary 实例，失败返回 null
     */
    public static RenderiumGraphBinary parseFromBytes(byte[] data) {
        if (data == null || data.length < HEADER_SIZE) {
            LOGGER.severe("数据太小，无法解析为 .rgb 格式");
            return null;
        }

        try {
            return new RenderiumGraphBinary(data);
        } catch (Exception e) {
            LOGGER.severe("解析 .rgb 数据失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 私有构造函数
     */
    private RenderiumGraphBinary(byte[] data) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // 解析 Header
        this.header = new Header();
        this.header.magic = buffer.getInt();
        this.header.version = buffer.getShort();
        this.header.flags = buffer.getShort();
        this.header.renderGraphOffset = buffer.getInt();
        this.header.renderGraphSize = buffer.getInt();
        this.header.paramTableOffset = buffer.getInt();
        this.header.paramTableSize = buffer.getInt();
        this.header.shaderTableOffset = buffer.getInt();
        this.header.shaderTableSize = buffer.getInt();
        this.header.luaBytecodeOffset = buffer.getInt();
        this.header.luaBytecodeSize = buffer.getInt();

        // 跳过剩余的 Header 空间（保留给未来扩展）
        buffer.position(HEADER_SIZE);

        // 验证 Header
        if (!this.header.isValid()) {
            throw new IOException("无效的 .rgb Header (magic=0x" +
                    Integer.toHexString(this.header.magic) +
                    ", version=" + this.header.version + ")");
        }

        // 读取 RenderGraphDescriptor
        this.renderGraphData = readSection(buffer,
                this.header.renderGraphOffset, this.header.renderGraphSize);

        // 读取 ParameterTable
        this.paramTableData = readSection(buffer,
                this.header.paramTableOffset, this.header.paramTableSize);

        // 读取 ShaderTable
        this.shaderTable = parseShaderTable(buffer,
                this.header.shaderTableOffset, this.header.shaderTableSize);

        // 读取 LuaBytecode（可选）
        this.luaBytecode = readSection(buffer,
                this.header.luaBytecodeOffset, this.header.luaBytecodeSize);

        LOGGER.info("═══ .rgb 文件加载成功 ═══");
        LOGGER.info("  Version: " + this.header.version);
        LOGGER.info("  Flags: 0x" + Integer.toHexString(this.header.flags));
        LOGGER.info("  Shaders: " + this.shaderTable.size());
        LOGGER.info("  Has Lua: " + this.header.hasLuaBytecode());
        LOGGER.info("═════════════════════════");
    }

    // ==================== 内部解析方法 ====================

    /**
     * 读取一个数据段
     */
    private byte[] readSection(ByteBuffer buffer, int offset, int size) {
        if (offset == 0 || size == 0) {
            return new byte[0];
        }

        buffer.position(offset);
        byte[] data = new byte[size];
        buffer.get(data);
        return data;
    }

    /**
     * 解析 Shader Table
     *
     * Shader Table 格式：
     * - uint32_t shaderCount
     * - 对于每个 shader:
     *   - uint32_t nameLength
     *   - char[nameLength] name (UTF-8)
     *   - uint32_t spirvSize
     *   - byte[spirvSize] spirvData
     */
    private Map<String, byte[]> parseShaderTable(ByteBuffer buffer, int offset, int size) {
        Map<String, byte[]> shaders = new HashMap<>();

        if (offset == 0 || size == 0) {
            return shaders;
        }

        buffer.position(offset);
        int shaderCount = buffer.getInt();

        for (int i = 0; i < shaderCount; i++) {
            // 读取名称
            int nameLength = buffer.getInt();
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            // 读取 SPIR-V 数据
            int spirvSize = buffer.getInt();
            byte[] spirvData = new byte[spirvSize];
            buffer.get(spirvData);

            shaders.put(name, spirvData);
            
            LOGGER.fine("  Shader: " + name + " (" + spirvSize + " bytes)");
        }

        return shaders;
    }

    // ==================== 公共查询接口 ====================

    /** 获取 Header */
    public Header getHeader() { return header; }

    /** 获取标志位 */
    public short getFlags() { return header.flags; }

    /** 是否包含自定义 Shader */
    public boolean hasCustomShaders() { 
        return (header.flags & FLAG_HAS_CUSTOM_SHADERS) != 0; 
    }

    /** 是否使用注入模式 */
    public boolean isInjectionMode() { 
        return (header.flags & FLAG_INJECTION_MODE) != 0; 
    }

    /** 是否使用夺舍模式 */
    public boolean isTakeoverMode() { 
        return (header.flags & FLAG_TAKEOVER_MODE) != 0; 
    }

    /** 是否包含 Lua 脚本 */
    public boolean hasLuaBytecode() { 
        return header.hasLuaBytecode(); 
    }

    /** 获取 RenderGraphDescriptor 数据 */
    public byte[] getRenderGraphData() { return renderGraphData; }

    /** 获取参数表数据 */
    public byte[] getParamTableData() { return paramTableData; }

    /** 获取 Lua 字节码 */
    public byte[] getLuaBytecode() { return luaBytecode; }

    /** 获取所有 Shader 名称 */
    public Iterable<String> getShaderNames() { return shaderTable.keySet(); }

    /** 获取特定 Pass 的 SPIR-V 数据 */
    public byte[] getSpirvForPass(String passName) { 
        return shaderTable.get(passName); 
    }

    /** 获取 Shader 数量 */
    public int getShaderCount() { return shaderTable.size(); }

    /** 获取 Pass 数量（从 RenderGraphDescriptor 解析） */
    public int getPassCount() {
        // TODO: 从 renderGraphData 中解析实际的 Pass 数量
        // 临时返回 Shader 数量作为估计
        return shaderTable.size();
    }

    // ==================== 参数访问接口 ====================

    /**
     * 获取解析后的参数表
     *
     * 返回参数名到值的映射
     */
    public synchronized Map<String, Float> getParameterTable() {
        if (parsedParameters == null && paramTableData.length > 0) {
            parsedParameters = parseParameterTable(paramTableData);
        }
        return parsedParameters;
    }

    /**
     * 解析参数表
     *
     * ParameterTable 格式：
     * - uint32_t paramCount
     * - 对于每个参数:
     *   - uint32_t nameLength
     *   - char[nameLength] name (UTF-8)
     *   - float value
     */
    private Map<String, Float> parseParameterTable(byte[] data) {
        Map<String, Float> params = new HashMap<>();

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int paramCount = buffer.getInt();

        for (int i = 0; i < paramCount; i++) {
            int nameLength = buffer.getInt();
            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);
            String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            float value = buffer.getFloat();

            params.put(name, value);
        }

        return params;
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建新的 .rgb 构建器
     *
     * 用于程序化生成 .rgb 文件
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder 类 ====================

    /**
     * .rgb 文件构建器
     */
    public static class Builder {
        private short flags = 0;
        private byte[] renderGraphData = new byte[0];
        private final Map<String, Float> parameters = new HashMap<>();
        private final Map<String, byte[]> shaders = new HashMap<>();
        private byte[] luaBytecode = null;

        public Builder setFlags(short flags) {
            this.flags = flags;
            return this;
        }

        public Builder addFlag(short flag) {
            this.flags |= flag;
            return this;
        }

        public Builder setRenderGraphData(byte[] data) {
            this.renderGraphData = (data != null) ? data : new byte[0];
            return this;
        }

        public Builder addParameter(String name, float value) {
            this.parameters.put(name, value);
            return this;
        }

        public Builder addShader(String passName, byte[] spirvData) {
            if (passName != null && spirvData != null) {
                this.shaders.put(passName, spirvData);
                
                // 自动设置 HAS_CUSTOM_SHADERS 标志
                if ((this.flags & FLAG_HAS_CUSTOM_SHADERS) == 0) {
                    this.flags |= FLAG_HAS_CUSTOM_SHADERS;
                }
            }
            return this;
        }

        public Builder setLuaBytecode(byte[] bytecode) {
            this.luaBytecode = bytecode;
            if (bytecode != null && bytecode.length > 0) {
                this.flags |= FLAG_HAS_LUA_SCRIPT;
            }
            return this;
        }

        /**
         * 构建 .rgb 二进制数据
         */
        public byte[] build() {
            // 计算各段大小
            int paramTableSize = calculateParamTableSize();
            int shaderTableSize = calculateShaderTableSize();
            int luaSize = (luaBytecode != null) ? luaBytecode.length : 0;

            // 计算偏移量
            int renderGraphOffset = HEADER_SIZE;
            int paramTableOffset = renderGraphOffset + renderGraphData.length;
            int shaderTableOffset = paramTableOffset + paramTableSize;
            // Lua 偏移量：仅在存在 Lua 数据时计算
            int luaOffset = (luaSize > 0) ? shaderTableOffset + shaderTableSize : 0;

            // 计算总缓冲区大小：以最后一个有效数据段的末尾为准
            // 注意：即使没有 Lua 数据，也必须包含 Shader Table 及之前的所有段
            int totalSize;
            if (luaSize > 0) {
                totalSize = luaOffset + luaSize;
            } else {
                totalSize = shaderTableOffset + shaderTableSize;
            }

            // 分配总缓冲区
            ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);

            // 写入 Header
            buffer.putInt(MAGIC);              // magic
            buffer.putShort(VERSION);          // version
            buffer.putShort(flags);             // flags
            buffer.putInt(renderGraphOffset);  // renderGraphOffset
            buffer.putInt(renderGraphData.length);  // renderGraphSize
            buffer.putInt(paramTableOffset);   // paramTableOffset
            buffer.putInt(paramTableSize);      // paramTableSize
            buffer.putInt(shaderTableOffset);  // shaderTableOffset
            buffer.putInt(shaderTableSize);     // shaderTableSize
            buffer.putInt(luaOffset);           // luaBytecodeOffset
            buffer.putInt(luaSize);             // luaBytecodeSize

            // 填充 Header 到 64 bytes
            while (buffer.position() < HEADER_SIZE) {
                buffer.put((byte) 0);
            }

            // 写入各段数据
            buffer.put(renderGraphData);
            writeParamTable(buffer);
            writeShaderTable(buffer);
            if (luaBytecode != null) {
                buffer.put(luaBytecode);
            }

            return buffer.array();
        }

        private int calculateParamTableSize() {
            // uint32 count + sum of (uint32 nameLen + name + float)
            int size = 4;  // paramCount
            for (Map.Entry<String, Float> entry : parameters.entrySet()) {
                size += 4 + entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 4;
            }
            return size;
        }

        private int calculateShaderTableSize() {
            // uint32 count + sum of (uint32 nameLen + name + uint32 spirvSize + spirv)
            int size = 4;  // shaderCount
            for (Map.Entry<String, byte[]> entry : shaders.entrySet()) {
                size += 4 + entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
                        4 + entry.getValue().length;
            }
            return size;
        }

        private void writeParamTable(ByteBuffer buffer) {
            buffer.putInt(parameters.size());
            for (Map.Entry<String, Float> entry : parameters.entrySet()) {
                byte[] nameBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buffer.putInt(nameBytes.length);
                buffer.put(nameBytes);
                buffer.putFloat(entry.getValue());
            }
        }

        private void writeShaderTable(ByteBuffer buffer) {
            buffer.putInt(shaders.size());
            for (Map.Entry<String, byte[]> entry : shaders.entrySet()) {
                byte[] nameBytes = entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buffer.putInt(nameBytes.length);
                buffer.put(nameBytes);
                byte[] spirv = entry.getValue();
                buffer.putInt(spirv.length);
                buffer.put(spirv);
            }
        }

        /**
         * 构建并保存到文件
         */
        public void buildToFile(Path outputFile) throws IOException {
            byte[] data = build();
            Files.write(outputFile, data);
            LOGGER.info(".rgb 文件已创建: " + outputFile + " (" + data.length + " bytes)");
        }
    }
}
