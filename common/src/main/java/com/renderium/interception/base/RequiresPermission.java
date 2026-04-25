package com.renderium.interception.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限要求注解
 * 用于标记需要特定权限才能执行的方法或类
 * 
 * <p>使用场景：</p>
 * <ul>
 *   <li>标注在方法上：表示调用该方法需要指定的权限</li>
 *   <li>标注在类上：表示该类中的所有方法都需要指定的权限</li>
 * </ul>
 * 
 * <p>该注解与 {@link PermissionValidator} 配合使用，
 * 在运行时进行权限校验，确保只有具备足够权限的调用者才能执行敏感操作。</p>
 * 
 * <p>示例用法：</p>
 * <pre>{@code
 * // 标注单个权限要求
 * @RequiresPermission(InterceptionPermission.ACCESS_VULKAN_API)
 * public void createVulkanDevice() {
 *     // Vulkan 设备创建逻辑
 * }
 * 
 * // 标注多个权限要求（使用 value 数组）
 * @RequiresPermission({
 *     InterceptionPermission.READ_FRAME_DATA,
 *     InterceptionPermission.APPLY_SUPER_RESOLUTION
 * })
 * public void processFrameWithUpscaling(FrameData frame) {
 *     // 帧处理与超分辨率逻辑
 * }
 * }</pre>
 * 
 * @see PermissionValidator
 * @see InterceptionPermission
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    
    /**
     * 获取执行目标所需的一个或多个权限
     * 
     * <p>当指定多个权限时，调用方需要拥有所有列出的权限才能通过验证（AND 逻辑）。</p>
     * 
     * @return 所需的权限枚举数组，默认为空数组（表示无特殊权限要求）
     */
    InterceptionPermission[] value() default {};
    
    /**
     * 权限不足时的错误消息
     * 
     * <p>当自定义此字段时，权限校验失败将抛出包含此消息的 SecurityException。
     * 如果不指定，将使用默认的权限不足提示信息。</p>
     * 
     * @return 自定义的错误提示文本，默认为空字符串
     */
    String message() default "";
}
