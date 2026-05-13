package com.ranecc.renderium.feature.intercept.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 权限验证器
 * 提供线程安全的权限检查和验证功能，用于保护拦截层的敏感操作
 * 
 * <p>核心职责：</p>
 * <ul>
 *   <li>管理当前已授予的权限集合（位掩码模式）</li>
 *   <li>提供非抛出异常的权限查询接口（{@link #hasPermission}）</li>
 *   <li>提供强制权限校验接口（{@link #requirePermission}），失败时抛出 SecurityException</li>
 *   <li>支持运行时动态授予/撤销权限</li>
 * </ul>
 * 
 * <p>线程安全性：</p>
 * <ul>
 *   <li>内部使用 {@link AtomicInteger} 存储权限状态，保证原子性读写</li>
 *   <li>所有公开方法均为线程安全，可在多线程环境下并发调用</li>
 * </ul>
 * 
 * <p>使用示例：</p>
 * <pre>{@code
 * // 创建验证器并授予权限
 * PermissionValidator validator = new PermissionValidator();
 * validator.grantPermission(InterceptionPermission.READ_RENDER_STATE);
 * validator.grantPermission(InterceptionPermission.APPLY_SUPER_RESOLUTION);
 * 
 * // 方式一：非阻塞式检查（返回布尔值）
 * if (validator.hasPermission(InterceptionPermission.ACCESS_VULKAN_API)) {
 *     createVulkanDevice();  // 安全执行
 * }
 * 
 * // 方式二：强制检查（无权限时抛出异常）
 * try {
 *     validator.requirePermission(InterceptionPermission.READ_FRAME_DATA);
 *     processRawFrameData();  // 有权限才执行
 * } catch (SecurityException e) {
 *     logger.warn("帧数据访问被拒绝: {}", e.getMessage());
 * }
 * }</pre>
 * 
 * @see InterceptionPermission
 * @see RequiresPermission
 */
public class PermissionValidator {
    
    /** 日志记录器 */
    private static final Logger logger = LoggerFactory.getLogger(PermissionValidator.class);
    
    /** 当前已授予的权限集合（位掩码），使用 AtomicInteger 保证线程安全 */
    private final AtomicInteger grantedPermissions;
    
    /**
     * 默认构造函数
     * 初始化一个没有任何权限的验证器实例
     */
    public PermissionValidator() {
        this.grantedPermissions = new AtomicInteger(0);
        logger.debug("权限验证器已初始化，当前权限集为空");
    }
    
    /**
     * 带初始权限的构造函数
     * 
     * @param initialPermissions 初始授予的权限位掩码值
     */
    public PermissionValidator(int initialPermissions) {
        this.grantedPermissions = new AtomicInteger(initialPermissions);
        logger.debug("权限验证器已初始化，初始权限掩码: 0x{}", Integer.toHexString(initialPermissions));
    }
    
    // ==================== 权限查询方法 ====================
    
    /**
     * 检查当前是否拥有指定的单个权限（非阻塞方式）
     * 
     * <p>该方法不会抛出异常，仅返回布尔值表示权限状态。
     * 适用于条件判断场景，调用方可根据返回值决定后续逻辑。</p>
     * 
     * @param permission 要检查的权限枚举项
     * @return 如果当前拥有该权限返回 true，否则返回 false
     * 
     * @throws IllegalArgumentException 当 permission 参数为 null 时抛出
     * 
     * <pre>{@code
     * // 示例：在执行 Vulkan 操作前进行权限预检
     * if (validator.hasPermission(InterceptionPermission.ACCESS_VULKAN_API)) {
     *     vulkanCommandBuffer.begin();
     * }
     * }</pre>
     */
    public boolean hasPermission(InterceptionPermission permission) {
        if (permission == null) {
            logger.warn("权限检查失败: 传入的权限参数为 null");
            throw new IllegalArgumentException("权限参数不能为 null");
        }
        
        int currentPermissions = grantedPermissions.get();
        boolean result = (currentPermissions & permission.getBitmask()) != 0;
        
        if (logger.isTraceEnabled()) {
            logger.trace("权限检查结果: {} -> {}", permission.name(), result ? "允许" : "拒绝");
        }
        
        return result;
    }
    
    /**
     * 检查当前是否拥有所有指定的权限（AND 逻辑）
     * 
     * <p>当传入多个权限时，必须全部拥有才会返回 true。</p>
     * 
     * @param permissions 要检查的权限枚举数组
     * @return 如果当前拥有所有指定权限返回 true，否则返回 false
     * 
     * @throws IllegalArgumentException 当 permissions 数组为 null 或包含 null 元素时抛出
     */
    public boolean hasAllPermissions(InterceptionPermission... permissions) {
        if (permissions == null || permissions.length == 0) {
            logger.warn("批量权限检查失败: 权限数组为空或 null");
            throw new IllegalArgumentException("权限数组不能为 null 或空");
        }
        
        int currentPermissions = grantedPermissions.get();
        for (InterceptionPermission permission : permissions) {
            if (permission == null) {
                logger.warn("批量权限检查失败: 权限数组中包含 null 元素");
                throw new IllegalArgumentException("权限数组中不能包含 null 元素");
            }
            if ((currentPermissions & permission.getBitmask()) == 0) {
                if (logger.isDebugEnabled()) {
                    logger.debug("批量权限检查未通过: 缺少权限 {}", permission.name());
                }
                return false;
            }
        }
        
        logger.trace("批量权限检查通过: 共 {} 项权限均满足", permissions.length);
        return true;
    }
    
    /**
     * 强制要求拥有指定权限（阻塞方式）
     * 
     * <p>如果当前不拥有该权限，将立即抛出 {@link SecurityException}。
     * 适用于必须具备权限才能继续执行的敏感操作。</p>
     * 
     * @param permission 必须拥有的权限枚举项
     * 
     * @throws SecurityException 当当前不拥有该权限时抛出，消息包含缺失权限的名称和描述
     * @throws IllegalArgumentException 当 permission 参数为 null 时抛出
     * 
     * <pre>{@code
     * // 示例：在执行模组行为修改前强制校验权限
     * public void modifyModBehavior(ModAdapter mod, BehaviorConfig config) {
     *     validator.requirePermission(InterceptionPermission.MODIFY_MOD_BEHAVIOR);
     *     // 到此处说明权限校验通过，可安全执行修改操作
     *     mod.applyBehaviorChange(config);
     * }
     * }</pre>
     */
    public void requirePermission(InterceptionPermission permission) {
        if (permission == null) {
            logger.error("权限强制校验失败: 传入的权限参数为 null");
            throw new IllegalArgumentException("权限参数不能为 null");
        }
        
        if (!hasPermission(permission)) {
            String errorMsg = String.format(
                "权限不足: 需要 [%s - %s]，当前权限级别不够",
                permission.name(),
                permission.getDescription()
            );
            logger.error("SecurityException: {}", errorMsg);
            throw new SecurityException(errorMsg);
        }
        
        logger.debug("权限校验通过: {}", permission.name());
    }
    
    /**
     * 强制要求拥有所有指定的权限（AND 逻辑）
     * 
     * @param permissions 必须全部拥有的权限枚举数组
     * 
     * @throws SecurityException 当缺少任意一项权限时抛出，消息列出所有缺失的权限
     * @throws IllegalArgumentException 当 permissions 数组为 null 或空时抛出
     */
    public void requireAllPermissions(InterceptionPermission... permissions) {
        if (permissions == null || permissions.length == 0) {
            logger.error("批量权限强制校验失败: 权限数组为空或 null");
            throw new IllegalArgumentException("权限数组不能为 null 或空");
        }
        
        int currentPermissions = grantedPermissions.get();
        StringBuilder missingPermissions = new StringBuilder();
        
        for (InterceptionPermission permission : permissions) {
            if (permission == null) {
                logger.error("批量权限强制校验失败: 权限数组中包含 null 元素");
                throw new IllegalArgumentException("权限数组中不能包含 null 元素");
            }
            if ((currentPermissions & permission.getBitmask()) == 0) {
                if (missingPermissions.length() > 0) {
                    missingPermissions.append(", ");
                }
                missingPermissions.append(permission.name());
            }
        }
        
        if (missingPermissions.length() > 0) {
            String errorMsg = String.format(
                "权限不足: 缺少以下权限 [%s]",
                missingPermissions.toString()
            );
            logger.error("SecurityException: {}", errorMsg);
            throw new SecurityException(errorMsg);
        }
        
        logger.debug("批量权限校验通过: 共 {} 项权限均满足", permissions.length);
    }
    
    // ==================== 权限管理方法 ====================
    
    /**
     * 授予指定的权限
     * 
     * <p>使用原子性的按位或操作添加权限，支持并发调用。</p>
     * 
     * @param permission 要授予的权限枚举项
     * @return 授予后的完整权限位掩码值
     * 
     * @throws IllegalArgumentException 当 permission 参数为 null 时抛出
     */
    public int grantPermission(InterceptionPermission permission) {
        if (permission == null) {
            logger.warn("授权失败: 传入的权限参数为 null");
            throw new IllegalArgumentException("权限参数不能为 null");
        }
        
        int newValue;
        int oldValue;
        do {
            oldValue = grantedPermissions.get();
            newValue = oldValue | permission.getBitmask();
        } while (!grantedPermissions.compareAndSet(oldValue, newValue));
        
        logger.info("权限已授予: {} (0x{})，当前总权限掩码: 0x{}", 
            permission.name(), 
            Integer.toHexString(permission.getBitmask()),
            Integer.toHexString(newValue)
        );
        
        return newValue;
    }
    
    /**
     * 撤销指定的权限
     * 
     * <p>使用原子性的按位与取反操作移除权限，支持并发调用。</p>
     * 
     * @param permission 要撤销的权限枚举项
     * @return 撤销后的完整权限位掩码值
     * 
     * @throws IllegalArgumentException 当 permission 参数为 null 时抛出
     */
    public int revokePermission(InterceptionPermission permission) {
        if (permission == null) {
            logger.warn("撤权失败: 传入的权限参数为 null");
            throw new IllegalArgumentException("权限参数不能为 null");
        }
        
        int newValue;
        int oldValue;
        do {
            oldValue = grantedPermissions.get();
            newValue = oldValue & ~permission.getBitmask();
        } while (!grantedPermissions.compareAndSet(oldValue, newValue));
        
        logger.info("权限已撤销: {} (0x{})，当前总权限掩码: 0x{}", 
            permission.name(), 
            Integer.toHexString(permission.getBitmask()),
            Integer.toHexString(newValue)
        );
        
        return newValue;
    }
    
    /**
     * 获取当前完整的权限位掩码值
     * 
     * <p>返回值的每一位代表是否拥有对应的 {@link InterceptionPermission} 权限。
     * 可用于持久化存储或跨组件传递权限状态。</p>
     * 
     * @return 当前权限集合的位掩码整数值
     */
    public int getGrantedPermissions() {
        return grantedPermissions.get();
    }
    
    /**
     * 检查当前是否拥有任何权限
     * 
     * @return 如果至少拥有一项权限返回 true，否则返回 false
     */
    public boolean hasAnyPermission() {
        return grantedPermissions.get() != 0;
    }
    
    /**
     * 清除所有已授予的权限
     * 
     * <p>将权限集重置为初始状态（无任何权限）。</p>
     */
    public void clearAllPermissions() {
        grantedPermissions.set(0);
        logger.warn("所有权限已被清除，权限掩码重置为 0x0");
    }
}
