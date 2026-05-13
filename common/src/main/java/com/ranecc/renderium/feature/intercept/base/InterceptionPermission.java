package com.ranecc.renderium.feature.intercept.base;

/**
 * 拦截层权限枚举
 * 定义各个功能模块所需的权限级别，使用位掩码模式
 * 
 * <p>使用说明：</p>
 * <ul>
 *   <li>每个权限值都是2的幂次方，支持通过位运算进行组合</li>
 *   <li>多个权限可以通过按位或（|）组合成一个权限集</li>
 *   <li>权限检查时使用按位与（&）操作判断是否拥有某项权限</li>
 * </ul>
 * 
 * <p>示例场景：</p>
 * <pre>{@code
 * // 为模组（如 Sodium 渲染模组）分配基础读取和检测权限
 * int modPermissions = InterceptionPermission.READ_RENDER_STATE.getBitmask()
 *                   | InterceptionPermission.DETECT_MODS.getBitmask();
 * 
 * // 检查是否拥有写入权限
 * boolean canWrite = (modPermissions & InterceptionPermission.WRITE_RENDER_STATE.getBitmask()) != 0;
 * }</pre>
 */
public enum InterceptionPermission {
    
    // ==================== 基础权限 (Bit 0-1) ====================
    
    /**
     * 读取渲染状态权限
     * <p>允许读取当前的渲染上下文、帧缓冲区状态等只读信息</p>
     * <p>位掩码值: 1 (2^0)</p>
     */
    READ_RENDER_STATE(1, "读取渲染状态"),
    
    /**
     * 写入渲染状态权限
     * <p>允许修改渲染状态，包括切换着色器、修改渲染参数等</p>
     * <p>位掩码值: 2 (2^1)</p>
     */
    WRITE_RENDER_STATE(2, "写入渲染状态"),
    
    // ==================== 模组交互权限 (Bit 2-4) ====================
    
    /**
     * 检测模组权限
     * <p>允许扫描和识别已加载的游戏模组及其版本信息</p>
     * <p>位掩码值: 4 (2^2)</p>
     */
    DETECT_MODS(4, "检测模组"),
    
    /**
     * 拦截模组输出权限
     * <p>允许拦截模组的渲染输出，在模组渲染完成后进行处理</p>
     * <p>位掩码值: 8 (2^3)</p>
     */
    INTERCEPT_MOD_OUTPUT(8, "拦截模组输出"),
    
    /**
     * 修改模组行为权限
     * <p>允许动态修改模组的渲染行为，替换或增强模组功能</p>
     * <p>位掩码值: 16 (2^4)</p>
     */
    MODIFY_MOD_BEHAVIOR(16, "修改模组行为"),
    
    // ==================== 后处理权限 (Bit 5-8) ====================
    
    /**
     * 读取帧数据权限
     * <p>允许访问原始帧数据，用于分析和处理</p>
     * <p>位掩码值: 32 (2^5)</p>
     */
    READ_FRAME_DATA(32, "读取帧数据"),
    
    /**
     * 应用超分辨率权限
     * <p>允许执行超分辨率算法（如DLSS、FSR等）提升画面质量</p>
     * <p>位掩码值: 64 (2^6)</p>
     */
    APPLY_SUPER_RESOLUTION(64, "应用超分辨率"),
    
    /**
     * 应用帧生成权限
     * <p>允许执行帧生成/插帧技术提升帧率</p>
     * <p>位掩码值: 128 (2^7)</p>
     */
    APPLY_FRAME_GENERATION(128, "应用帧生成"),
    
    /**
     * 应用后处理权限
     * <p>允许执行通用后处理效果（如色调映射、抗锯齿等）</p>
     * <p>位掩码值: 256 (2^8)</p>
     */
    APPLY_POST_PROCESSING(256, "应用后处理"),
    
    // ==================== 系统权限 (Bit 9-11) ====================
    
    /**
     * 访问 Vulkan API 权限
     * <p>允许直接调用 Vulkan API 进行底层图形操作</p>
     * <p>位掩码值: 512 (2^9)</p>
     */
    ACCESS_VULKAN_API(512, "访问 Vulkan API"),
    
    /**
     * 访问 OpenGL API 权限
     * <p>允许直接调用 OpenGL API 进行底层图形操作</p>
     * <p>位掩码值: 1024 (2^10)</p>
     */
    ACCESS_OPENGL_API(1024, "访问 OpenGL API"),
    
    /**
     * 修改 Swapchain 权限
     * <p>允许修改交换链配置，调整呈现模式等</p>
     * <p>位掩码值: 2048 (2^11)</p>
     */
    MODIFY_SWAPCHAIN(2048, "修改 Swapchain");
    
    /** 权限的位掩码值 */
    private final int bitmask;
    
    /** 权限的人类可读描述 */
    private final String description;
    
    /**
     * 构造权限枚举项
     * 
     * @param bitmask 权限的位掩码值（必须是2的幂次方）
     * @param description 权限的描述文本
     */
    InterceptionPermission(int bitmask, String description) {
        this.bitmask = bitmask;
        this.description = description;
    }
    
    /**
     * 获取权限的位掩码值
     * 
     * @return 该权限对应的位掩码整数值
     */
    public int getBitmask() { 
        return bitmask; 
    }
    
    /**
     * 获取权限的描述文本
     * 
     * @return 权限的人类可读描述字符串
     */
    public String getDescription() { 
        return description; 
    }
    
    /**
     * 根据位掩码值查找对应的权限枚举项
     * 
     * @param bitmask 要查找的位掩码值
     * @return 对应的权限枚举项，如果未找到则返回 null
     */
    public static InterceptionPermission fromBitmask(int bitmask) {
        for (InterceptionPermission permission : values()) {
            if (permission.bitmask == bitmask) {
                return permission;
            }
        }
        return null;
    }
    
    /**
     * 检查给定的权限集是否包含指定权限
     * 
     * @param permissionSet 当前的权限集合（位掩码组合）
     * @param permission 要检查的权限
     * @return 如果权限集中包含该权限返回 true，否则返回 false
     */
    public static boolean hasPermission(int permissionSet, InterceptionPermission permission) {
        return (permissionSet & permission.bitmask) != 0;
    }
}
