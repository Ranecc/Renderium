// Renderium - 可扩展 Shader 节点系统
// ShaderNodeFactory - 节点工厂模式
//
// 核心功能：
//   1. 根据 CompDescriptor 创建 PipelineNode 实例
//   2. 支持 SPIR-V 热重载（无需重启）
//   3. 参数自动绑定到 ParameterKnob 系统
//   4. 缓存已创建的节点实例（单例模式）
//
// 架构设计：
//   ShaderCompDescriptor ──→ ShaderNodeFactory ──→ PipelineNode 实例
//         (配置)                  (创建)            (运行时)
//                                  │
//                    ┌─────────────┼──────────────┐
//                    ▼             ▼              ▼
//              SPIRVShaderModule  ParameterKnob  InstanceCache

package com.ranecc.renderium.feature.shader.factory;

import com.ranecc.renderium.feature.shader.comp.ShaderCompDescriptor;
import com.ranecc.renderium.feature.shader.pipeline.parameter.*;
import com.ranecc.renderium.feature.shader.pipeline.parameter.impl.BoolKnob;
import com.ranecc.renderium.feature.shader.pipeline.parameter.impl.EnumKnob;
import com.ranecc.renderium.feature.shader.pipeline.parameter.impl.FloatKnob;
import com.ranecc.renderium.feature.shader.pipeline.parameter.impl.IntKnob;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.shader.pipeline.node.PipelineNode;
import com.ranecc.renderium.feature.shader.pipeline.parameter.ParameterKnob;
import com.ranecc.renderium.feature.shader.registry.ShaderNodeRegistry;
import com.ranecc.renderium.feature.shader.spirv.SPIRVShaderModule;
/**
 * Shader 节点工厂（单例）
 * <p>
 * 负责 {@link ShaderCompDescriptor} 到 {@link PipelineNode} 实例的完整创建流程。
 * 集成参数系统、SPIR-V 管理和实例缓存，提供统一的节点实例化入口。
 *
 * <h2>创建流程：</h2>
 * <pre>
 * ┌──────────────────┐     1. 查找描述符      ┌────────────────────┐
 * │ createNode(id)   │ ─────────────────────→ │ ShaderNodeRegistry │
 * └────────┬─────────┘                        └────────┬───────────┘
 *          │ 2. 获取 CompDescriptor                   │
 *          ▼                                        │
 * ┌──────────────────┐                              │
 * │ 创建 ParameterKnob│ ← 3. 从 parameters[] 构建    │
 * │ 列表并注册        │                              │
 * └────────┬─────────┘                              │
 *          │ 4. 加载 SPIR-V                          │
 *          ▼                                        │
 * ┌──────────────────┐     5. 创建/获取缓存         │
 * │ SPIRVShaderModule│ ← 6. 绑定到节点实例           │
 * └────────┬─────────┘                              │
 *          │ 7. 返回 PipelineNode                    │
 *          ▼                                        │
 * ┌──────────────────┐                              │
 * │ PipelineNode 实例 │ ← 8. 注册到 Registry.cache  │
 * └──────────────────┘                              │
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 获取工厂单例
 * ShaderNodeFactory factory = ShaderNodeFactory.getInstance();
 *
 * // 通过节点 ID 创建实例
 * PipelineNode pbrNode = factory.createNode("pbr_material");
 *
 * // 初始化节点（分配 GPU 资源）
 * pbrNode.initialize(renderContext);
 *
 * // 执行渲染逻辑
 * long outputTexture = pbrNode.execute(renderContext, inputColorHandle);
 *
 * // 热重载：更新 .comp 文件后重新创建
 * factory.reloadNode("pbr_material");
 * </pre>
 *
 * @see ShaderCompDescriptor
 * @see ShaderNodeRegistry
 * @see SPIRVShaderModule
 * @since 7.0.0
 */
public final class ShaderNodeFactory {

    private static final Logger LOGGER = Logger.getLogger("Renderium|ShaderNodeFactory");

    /** 单例实例 */
    private static volatile ShaderNodeFactory INSTANCE;

    /** 引用的全局注册表 */
    private final ShaderNodeRegistry registry;

    /**
     * 内置节点类型映射（节点 ID -> Java 类名）
     * <p>
     * 用于将 .comp 描述符映射到具体的 PipelineNode 实现类。
     * 支持通过配置或注解自动发现。
     */
    private final Map<String, String> builtinNodeTypeMap;

    /**
     * 自定义节点类型映射（用户注册的扩展类型）
     */
    private final Map<String, Class<? extends PipelineNode>> customNodeTypeMap;

    /**
     * 节点创建钩子列表（允许在创建前后插入自定义逻辑）
     */
    private final List<NodeCreationHook> creationHooks;

    /**
     * 工厂统计信息
     */
    private long totalCreatedCount = 0;
    private long cacheHitCount = 0;
    private long reloadCount = 0;

    // ==================== 枚举和接口定义 ====================

    /**
     * 节点创建策略
     */
    public enum CreationStrategy {
        /** 优先从缓存返回，缓存未命中才创建（默认） */
        CACHE_FIRST,
        /** 每次都创建新实例（不缓存） */
        ALWAYS_NEW,
        /** 强制重新创建并更新缓存 */
        FORCE_RECREATE
    }

    /**
     * 节点创建钩子接口
     * <p>
     * 允许在节点创建过程中插入自定义逻辑，
     * 如额外的初始化、AOP 拦截、监控等。
     */
    public interface NodeCreationHook {
        /**
         * 创建前回调（可修改描述符或阻止创建）
         *
         * 【方法参数】
         * @param descriptor ShaderCompDescriptor - 即将用于创建的描述符
         *
         * @return boolean - true 允许继续创建，false 阻止创建
         */
        boolean preCreate(ShaderCompDescriptor descriptor);

        /**
         * 创建后回调（可对实例进行额外处理）
         *
         * 【方法参数】
         * @param instance   PipelineNode       - 新创建的节点实例
         * @param descriptor ShaderCompDescriptor - 原始描述符
         */
        void postCreate(PipelineNode instance, ShaderCompDescriptor descriptor);
    }

    // ==================== 单例模式 ====================

    /**
     * 私有构造函数
     */
    private ShaderNodeFactory() {
        this.registry = ShaderNodeRegistry.getInstance();
        this.builtinNodeTypeMap = initBuiltinTypeMap();
        this.customNodeTypeMap = new ConcurrentHashMap<>();
        this.creationHooks = new ArrayList<>();

        LOGGER.info("ShaderNodeFactory 单例已初始化");
    }

    /**
     * 获取全局单例实例
     *
     * @return ShaderNodeFactory - 全局唯一实例
     */
    public static ShaderNodeFactory getInstance() {
        if (INSTANCE == null) {
            synchronized (ShaderNodeFactory.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ShaderNodeFactory();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 初始化内置节点类型映射
     *
     * @return Map - 节点 ID -> 实现类全限定名的映射
     */
    private Map<String, String> initBuiltinTypeMap() {
        Map<String, String> map = new LinkedHashMap<>();

        // ===== 预计算阶段 (PRE_RENDER) =====
        map.put("skybox", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.SkyBoxNode");
        map.put("shadow_style", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.ShadowStyleNode");

        // ===== G-Buffer 阶段 (GBUFFER) =====
        map.put("pbr_material", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.PBRMaterialNode");

        // ===== 光照阶段 (LIGHTING) =====
        map.put("lighting", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.LightingNode");
        map.put("reflection", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.ReflectionNode");
        map.put("ray_tracing", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.RayTracingNode");

        // ===== 后处理阶段 (POST_PROCESS) =====
        map.put("exposure", "com.ranecc.renderium.feature.shader.pipeline.node.builtin.ExposureNode");

        return Collections.unmodifiableMap(map);
    }

    // ==================== 核心 API：节点创建 ====================

    /**
     * 根据节点 ID 创建（或从缓存获取）PipelineNode 实例
     * <p>
     * 这是工厂的主要入口方法。完整的创建流程：
     * <ol>
     *   <li>从 Registry 获取 CompDescriptor</li>
     *   <li>检查缓存（如果策略为 CACHE_FIRST）</li>
     *   <li>调用 preCreate 钩子</li>
     *   <li>确定实现类类型</li>
     *   <li>通过反射创建实例</li>
     *   <li>构建并绑定 ParameterKnob 列表</li>
     *   <li>加载并关联 SPIR-V 模块</li>
     *   <li>调用 postCreate 钩子</li>
     *   <li>缓存实例</li>
     * </ol>
     *
     * 【方法参数】
     * @param nodeId String - 节点唯一标识符（对应 .comp 文件中的 metadata.id）
     *
     * @return PipelineNode - 创建的节点实例，失败返回 null
     */
    public PipelineNode createNode(String nodeId) {
        return createNode(nodeId, CreationStrategy.CACHE_FIRST);
    }

    /**
     * 根据节点 ID 和策略创建 PipelineNode 实例
     *
     * 【方法参数】
     * @param nodeId   String          - 节点 ID
     * @param strategy CreationStrategy - 创建策略
     *
     * @return PipelineNode - 节点实例，失败返回 null
     */
    public PipelineNode createNode(String nodeId, CreationStrategy strategy) {
        if (nodeId == null || nodeId.isEmpty()) {
            LOGGER.warning("createNode(): nodeId 不能为空");
            return null;
        }

        // Step 1: 获取描述符
        ShaderCompDescriptor descriptor = registry.getDescriptor(nodeId);
        if (descriptor == null) {
            LOGGER.warning(String.format("createNode(): 节点 %s 未在注册表中找到", nodeId));
            return null;
        }

        // Step 2: 缓存检查
        if (strategy == CreationStrategy.CACHE_FIRST) {
            PipelineNode cached = registry.getNodeInstance(nodeId);
            if (cached != null) {
                cacheHitCount++;
                LOGGER.fine(String.format("createNode(): 缓存命中 %s", nodeId));
                return cached;
            }
        }

        // Step 3: 调用 preCreate 钩子
        for (NodeCreationHook hook : creationHooks) {
            try {
                if (!hook.preCreate(descriptor)) {
                    LOGGER.fine(String.format("createNode(): 钩子阻止了节点 %s 的创建", nodeId));
                    return null;
                }
            } catch (Exception e) {
                LOGGER.warning(String.format("preCreate 钩子异常 [%s]: %s", nodeId, e.getMessage()));
            }
        }

        // Step 4: 确定实现类并创建实例
        PipelineNode instance = doCreateInstance(descriptor);
        if (instance == null) {
            LOGGER.severe(String.format("createNode(): 无法创建节点 %s 的实例", nodeId));
            return null;
        }

        // Step 5: 绑定参数
        bindParameters(instance, descriptor);

        // Step 6: 加载 SPIR-V（如果有）
        loadSpirvModule(instance, descriptor);

        // Step 7: 调用 postCreate 钩子
        for (NodeCreationHook hook : creationHooks) {
            try {
                hook.postCreate(instance, descriptor);
            } catch (Exception e) {
                LOGGER.warning(String.format("postCreate 钩子异常 [%s]: %s", nodeId, e.getMessage()));
            }
        }

        // Step 8: 缓存实例
        registry.cacheInstance(instance);

        totalCreatedCount++;

        LOGGER.fine(String.format("createNode(): 成功创建节点 %s [%s] (total=%d, cacheHits=%d)",
                nodeId, descriptor.getMetadata().getDisplayName(), totalCreatedCount, cacheHitCount));

        return instance;
    }

    /**
     * 根据描述符直接创建节点（跳过注册表查找）
     * <p>
     * 用于测试或需要显式传入自定义描述符的场景。
     *
     * 【方法参数】
     * @param descriptor ShaderCompDescriptor - 节点描述符
     *
     * @return PipelineNode - 节点实例，失败返回 null
     */
    public PipelineNode createFromDescriptor(ShaderCompDescriptor descriptor) {
        if (descriptor == null) return null;

        PipelineNode instance = doCreateInstance(descriptor);
        if (instance == null) return null;

        bindParameters(instance, descriptor);
        loadSpirvModule(instance, descriptor);

        // 自动注册到注册表（如果尚未注册）
        if (registry.getDescriptor(descriptor.getMetadata().getId()) == null) {
            registry.register(descriptor);
        }

        registry.cacheInstance(instance);
        totalCreatedCount++;

        return instance;
    }

    // ==================== 批量操作 API ====================

    /**
     * 批量创建多个节点
     * <p>
     * 按照依赖顺序创建，确保被依赖的节点先于依赖者创建。
     * 创建失败的节点会被记录但不中断整体流程。
     *
     * 【方法参数】
     * @param nodeIds String... - 要创建的节点 ID 列表
     *
     * @return Map&lt;String,PipelineNode&gt; - 创建结果映射（ID -> 实例），失败的条目值为 null
     */
    public Map<String, PipelineNode> createNodes(String... nodeIds) {
        if (nodeIds == null || nodeIds.length == 0) {
            return Collections.emptyMap();
        }

        // 先解析执行顺序
        ShaderNodeRegistry.ExecutionPlan plan = registry.resolveExecutionOrder();

        // 过滤出需要创建的节点
        Set<String> requestedIds = new HashSet<>(Arrays.asList(nodeIds));

        Map<String, PipelineNode> result = new LinkedHashMap<>();

        // 按拓扑排序顺序创建
        for (String id : plan.getOrderedNodes()) {
            if (requestedIds.contains(id)) {
                result.put(id, createNode(id));
            }
        }

        // 处理不在排序结果中的节点（可能无依赖信息）
        for (String id : nodeIds) {
            if (!result.containsKey(id)) {
                result.put(id, createNode(id));
            }
        }

        return result;
    }

    // ==================== 热重载 API ====================

    /**
     * 热重载指定节点
     * <p>
     * 释放旧实例，重新从 .comp 描述符创建新实例。
     * 用于开发调试时的快速迭代。
     *
     * <h3>热重载流程：</h3>
     * <ol>
     *   <li>释放旧实例资源（dispose()）</li>
     *   <li>清除实例缓存</li>
     *   <li>可选：重新读取 .comp 文件（如果文件已修改）</li>
     *   <li>使用 FORCE_RECREATE 策略创建新实例</li>
     *   <li>自动初始化（如果提供了 RenderContext）</li>
     * </ol>
     *
     * 【方法参数】
     * @param nodeId String - 要重载的节点 ID
     *
     * @return PipelineNode - 新创建的实例，失败返回 null
     */
    public PipelineNode reloadNode(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) return null;

        LOGGER.info(String.format("正在热重载节点: %s", nodeId));

        // 1. 释放旧实例
        registry.removeCachedInstance(nodeId);

        // 2. 使用强制重建策略创建新实例
        PipelineNode newInstance = createNode(nodeId, CreationStrategy.FORCE_RECREATE);

        reloadCount++;

        if (newInstance != null) {
            LOGGER.info(String.format("节点 %s 热重载成功", nodeId));
        } else {
            LOGGER.warning(String.format("节点 %s 热重载失败", nodeId));
        }

        return newInstance;
    }

    /**
     * 热重载所有已缓存的节点
     *
     * @return int - 成功重载的节点数量
     */
    public int reloadAll() {
        Set<String> allIds = registry.getAllNodeIds();
        int successCount = 0;

        for (String id : allIds) {
            if (reloadNode(id) != null) {
                successCount++;
            }
        }

        LOGGER.info(String.format("批量热重载完成: 成功=%d/%d", successCount, allIds.size()));
        return successCount;
    }

    // ==================== 类型注册 API ====================

    /**
     * 注册自定义节点类型
     * <p>
     * 允许插件开发者将自己的 PipelineNode 实现类注册到工厂，
     * 使其能够根据 .comp 描述符被正确实例化。
     *
     * 【方法参数】
     * @param nodeId      String                        - 节点 ID（与 .comp 中 metadata.id 对应）
     * @param nodeClass   Class&lt;? extends PipelineNode&gt; - 实现类
     */
    public void registerNodeType(String nodeId, Class<? extends PipelineNode> nodeClass) {
        if (nodeId == null || nodeClass == null) {
            throw new IllegalArgumentException("nodeId 和 nodeClass 不能为 null");
        }

        customNodeTypeMap.put(nodeId, nodeClass);
        LOGGER.fine(String.format("注册自定义节点类型: %s -> %s", nodeId, nodeClass.getName()));
    }

    /**
     * 注销自定义节点类型
     *
     * @param nodeId String - 节点 ID
     * @return boolean - 是否成功移除
     */
    public boolean unregisterNodeType(String nodeId) {
        return customNodeTypeMap.remove(nodeId) != null;
    }

    /**
     * 添加创建钩子
     *
     * @param hook NodeCreationHook - 钩子实例
     */
    public void addCreationHook(NodeCreationHook hook) {
        if (hook != null) {
            creationHooks.add(hook);
        }
    }

    /**
     * 移除创建钩子
     *
     * @param hook NodeCreationHook - 要移除的钩子
     */
    public void removeCreationHook(NodeCreationHook hook) {
        creationHooks.remove(hook);
    }

    // ==================== 内部核心方法 ====================

    /**
     * 执行实际的实例创建逻辑
     * <p>
     * 根据描述符确定使用哪个实现类，然后通过反射创建实例。
     * 查找优先级：customNodeTypeMap > builtinNodeTypeMap > 默认 GenericShaderNode
     *
     * 【方法参数】
     * @param descriptor ShaderCompDescriptor - 节点描述符
     *
     * @return PipelineNode - 创建的实例，失败返回 null
     */
    private PipelineNode doCreateInstance(ShaderCompDescriptor descriptor) {
        String nodeId = descriptor.getMetadata().getId();
        Class<? extends PipelineNode> nodeClass = null;

        // 1. 查找自定义类型
        nodeClass = customNodeTypeMap.get(nodeId);

        // 2. 查找内置类型
        if (nodeClass == null) {
            String className = builtinNodeTypeMap.get(nodeId);
            if (className != null) {
                try {
                    Class<?> rawClass = Class.forName(className);
                    if (PipelineNode.class.isAssignableFrom(rawClass)) {
                        @SuppressWarnings("unchecked")
                        Class<? extends PipelineNode> typedClass = (Class<? extends PipelineNode>) rawClass;
                        nodeClass = typedClass;
                    }
                } catch (ClassNotFoundException e) {
                    LOGGER.warning(String.format("内置节点类未找到: %s (节点 %s)", className, nodeId));
                }
            }
        }

        // 3. 回退到通用着色器节点（基于描述符动态生成行为）
        if (nodeClass == null) {
            LOGGER.fine(String.format("使用 GenericShaderNode 作为回退: %s", nodeId));
            nodeClass = GenericShaderNode.class;
        }

        // 4. 通过反射创建实例
        try {
            PipelineNode instance = nodeClass.getDeclaredConstructor().newInstance();
            LOGGER.fine(String.format("成功创建实例: %s -> %s", nodeId, nodeClass.getSimpleName()));
            return instance;
        } catch (Exception e) {
            LOGGER.severe(String.format("创建节点实例失败 [%s]: %s - %s",
                    nodeId, nodeClass.getName(), e.getMessage()));
            return null;
        }
    }

    /**
     * 将描述符中的参数定义绑定为 ParameterKnob 并关联到节点
     *
     * 【方法参数】
     * @param instance   PipelineNode       - 节点实例
     * @param descriptor ShaderCompDescriptor - 包含参数定义的描述符
     */
    private void bindParameters(PipelineNode instance, ShaderCompDescriptor descriptor) {
        List<ShaderCompDescriptor.ParameterDef> params = descriptor.getParameters();
        if (params.isEmpty()) return;

        try {
            // 如果节点实现了 ParameterAware 接口，直接设置参数
            if (instance instanceof ParameterAware) {
                ParameterAware aware = (ParameterAware) instance;
                List<ParameterKnob<?>> knobs = new ArrayList<>();

                for (ShaderCompDescriptor.ParameterDef param : params) {
                    ParameterKnob<?> knob = createParameterKnob(param);
                    if (knob != null) {
                        knobs.add(knob);
                        // 同时注册到全局 ParameterRegistry
                        registerToParameterRegistry(knob);
                    }
                }

                aware.setParameters(knobs);
                LOGGER.fine(String.format("绑定 %d 个参数到节点 %s", knobs.size(), instance.getId()));
            } else {
                LOGGER.fine(String.format("节点 %s 未实现 ParameterAware 接口，跳过参数绑定", instance.getId()));
            }
        } catch (Exception e) {
            LOGGER.warning(String.format("参数绑定异常 [%s]: %s", instance.getId(), e.getMessage()));
        }
    }

    /**
     * 根据参数定义创建对应的 ParameterKnob 实例
     *
     * 【方法参数】
     * @param paramDef ParameterDef - 参数定义
     *
     * @return ParameterKnob - 创建的参数旋钮，不支持的类型返回 null
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ParameterKnob<?> createParameterKnob(ShaderCompDescriptor.ParameterDef paramDef) {
        switch (paramDef.getType()) {
            case FLOAT:
                float defaultFloat = paramDef.getDefaultValue() instanceof Number ?
                        ((Number) paramDef.getDefaultValue()).floatValue() : 0.0f;
                float minF = 0f, maxF = 1f;
                if (paramDef.getRange() != null && paramDef.getRange().length >= 2) {
                    minF = ((Number) paramDef.getRange()[0]).floatValue();
                    maxF = ((Number) paramDef.getRange()[1]).floatValue();
                }
                return new FloatKnob.Builder(paramDef.getId())
                        .displayName(paramDef.getDisplayName())
                        .range(minF, maxF)
                        .defaultValue(defaultFloat)
                        .category(paramDef.getCategory())
                        .build();

            case INT:
                int defaultInt = paramDef.getDefaultValue() instanceof Number ?
                        ((Number) paramDef.getDefaultValue()).intValue() : 0;
                int minI = 0, maxI = 100;
                if (paramDef.getRange() != null && paramDef.getRange().length >= 2) {
                    minI = ((Number) paramDef.getRange()[0]).intValue();
                    maxI = ((Number) paramDef.getRange()[1]).intValue();
                }
                return new IntKnob.Builder(paramDef.getId())
                        .displayName(paramDef.getDisplayName())
                        .range(minI, maxI)
                        .defaultValue(defaultInt)
                        .category(paramDef.getCategory())
                        .build();

            case BOOL:
                boolean defaultBool = paramDef.getDefaultValue() instanceof Boolean ?
                        (Boolean) paramDef.getDefaultValue() : false;
                return new BoolKnob.Builder(paramDef.getId())
                        .displayName(paramDef.getDisplayName())
                        .defaultValue(defaultBool)
                        .category(paramDef.getCategory())
                        .build();

            case ENUM:
                String defaultEnum = paramDef.getDefaultValue() instanceof String ?
                        (String) paramDef.getDefaultValue() : "";
                return new EnumKnob.Builder(paramDef.getId())
                        .displayName(paramDef.getDisplayName())
                        .options(paramDef.getEnumOptions())
                        .defaultValue(defaultEnum)
                        .category(paramDef.getCategory())
                        .build();

            default:
                LOGGER.warning(String.format("暂不支持的参数类型: %s (%s)",
                        paramDef.getType(), paramDef.getId()));
                return null;
        }
    }

    /**
     * 将 ParameterKnob 注册到全局 ParameterRegistry
     *
     * @param knob ParameterKnob - 要注册的参数旋钮
     */
    private void registerToParameterRegistry(ParameterKnob<?> knob) {
        try {
            ParameterRegistry.getInstance().register(knob);
        } catch (Exception e) {
            // ParameterRegistry 可能尚未初始化，忽略注册错误
            LOGGER.fine(String.format("ParameterRegistry 注册跳过: %s", knob.getId()));
        }
    }

    /**
     * 加载 SPIR-V 模块并关联到节点
     *
     * 【方法参数】
     * @param instance   PipelineNode       - 节点实例
     * @param descriptor ShaderCompDescriptor - 包含 SPIR-V 引用的描述符
     */
    private void loadSpirvModule(PipelineNode instance, ShaderCompDescriptor descriptor) {
        ShaderCompDescriptor.SpirvReference spirvRef = descriptor.getSpirv();
        if (spirvRef == null) {
            LOGGER.fine(String.format("节点 %s 无 SPIR-V 引用，跳过加载", instance.getId()));
            return;
        }

        try {
            // 如果节点支持 SPIR-V 模块接口，进行关联
            if (instance instanceof SpirvCapable) {
                SPIRVShaderModule.SpirvReference ref = new SPIRVShaderModule.SpirvReference();
                ref.path = spirvRef.getPath();
                ref.entryPoint = spirvRef.getEntryPoint();
                SPIRVShaderModule module = SPIRVShaderModule.load(ref);
                ((SpirvCapable) instance).setSpirvModule(module);
                LOGGER.fine(String.format("SPIR-V 模块已加载并关联到节点 %s", instance.getId()));
            } else {
                LOGGER.fine(String.format("节点 %s 未实现 SpirvCapable 接口，SPIR-V 信息仅作记录", instance.getId()));
            }
        } catch (Exception e) {
            LOGGER.warning(String.format("加载 SPIR-V 模块失败 [%s]: %s", instance.getId(), e.getMessage()));
        }
    }

    // ==================== 辅助接口定义 ====================

    /**
     * 参数感知接口
     * <p>
     * 实现此接口的 PipelineNode 可以接收工厂创建的 ParameterKnob 列表。
     */
    public interface ParameterAware {
        /**
         * 设置参数列表
         *
         * @param parameters List&lt;ParameterKnob&lt;?&gt;&gt; - 参数旋钮列表
         */
        void setParameters(List<ParameterKnob<?>> parameters);

        /**
         * 获取当前参数列表
         *
         * @return List - 参数旋钮列表
         */
        List<ParameterKnob<?>> getParameters();
    }

    /**
     * SPIR-V 能力接口
     * <p>
     * 实现此接口的 PipelineNode 可以接收 SPIR-V 着色器模块。
     */
    public interface SpirvCapable {
        /**
         * 设置 SPIR-V 模块
         *
         * @param module SPIRVShaderModule - 着色器模块
         */
        void setSpirvModule(SPIRVShaderModule module);

        /**
         * 获取当前 SPIR-V 模块
         *
         * @return SPIRVShaderModule - 当前模块
         */
        SPIRVShaderModule getSpirvModule();
    }

    // ==================== 诊断 API ====================

    /**
     * 获取工厂统计信息
     *
     * @return String - 格式化的统计字符串
     */
    public String getStatistics() {
        return String.format(
                "ShaderNodeFactory{created=%d, cacheHits=%d, reloads=%d, hitRate=%.1f%%, customTypes=%d}",
                totalCreatedCount, cacheHitCount, reloadCount,
                totalCreatedCount > 0 ? (cacheHitCount * 100.0 / totalCreatedCount) : 0,
                customNodeTypeMap.size()
        );
    }

    /**
     * 重置统计计数器
     */
    public void resetStats() {
        totalCreatedCount = 0;
        cacheHitCount = 0;
        reloadCount = 0;
    }

    /**
     * 释放工厂资源
     */
    public void dispose() {
        customNodeTypeMap.clear();
        creationHooks.clear();
        resetStats();
        LOGGER.info("ShaderNodeFactory 资源已释放");
    }
}
