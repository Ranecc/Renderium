// Renderium - 动态参数配置系统
// Shader 节点参数配置容器 - 关联 PipelineNode 并批量应用 GPU Uniforms

package com.ranecc.renderium.feature.pipeline.parameter;

import com.ranecc.renderium.None;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.ranecc.renderium.feature.intercept.base.RenderContext;
import com.ranecc.renderium.feature.pipeline.parameter.ParameterKnob;
import com.ranecc.renderium.feature.pipeline.parameter.impl.BoolKnob;
import com.ranecc.renderium.feature.pipeline.parameter.impl.EnumKnob;
import com.ranecc.renderium.feature.pipeline.parameter.impl.FloatKnob;
import com.ranecc.renderium.feature.pipeline.parameter.impl.IntKnob;
import com.ranecc.renderium.feature.pipeline.node.PipelineNode;

/**
 * Shader 参数配置容器 (Shader Parameter Config)
 * <p>
 * 将一组 {@link ParameterKnob} 实例聚合为一个逻辑单元，
 * 关联到具体的 {@link PipelineNode}，提供批量将参数应用到 GPU Uniforms 的能力。
 *
 * <h2>设计目标：</h2>
 * <ul>
 *   <li><b>参数聚合</b>：将属于同一 Shader/PipelineNode 的参数组织在一起</li>
 *   <li><b>批量应用</b>：一次性将所有参数推送到 GPU Uniforms/UBO</li>
 *   <li><b>脏标记追踪</b>：仅重新上传发生变化的参数（性能优化）</li>
 *   <li><b>节点绑定</b>：与 PipelineNode 生命周期联动</li>
 * </ul>
 *
 * <h3>架构位置：</h3>
 * <pre>
 * ┌──────────────────────────────────────┐
 * │        ShaderParameterConfig         │
 * │  ┌────────────────────────────────┐  │
 * │  │  FloatKnob: normal_strength     │  │
 * │  │  IntKnob:   pcf_samples        │  │
 * │  │  EnumKnob:  filter_type        │  │
 * │  │  BoolKnob:  enable_normal_map  │  │
 * │  └─────────────┬──────────────────┘  │
 * │                │                      │
 * │    applyTo(RenderContext)             │
 * │                │                      │
 * ▼                ▼                      ▼
 * ┌──────────────────────────────────────┐
 * │          GPU Uniforms / UBO          │
 * │  u_normalStrength = 1.5              │
 * │  u_pcfSamples = 32                   │
 * │  u_filterType = 2                    │
 * │  u_enableNormalMap = true            │
 * └──────────────────────────────────────┘
 * </pre>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 创建配置容器并关联到阴影节点
 * ShaderParameterConfig shadowConfig = new ShaderParameterConfig.Builder("shadow_config")
 *     .displayName("阴影配置")
 *     .bindNode(shadowMapNode)
 *     // 添加参数
 *     .addParameter(new FloatKnob.Builder("normal_strength")
 *         .displayName("法线强度").range(0.1f, 2.0f).defaultValue(1.0f).build())
 *     .addParameter(new IntKnob.Builder("pcf_samples")
 *         .displayName("PCF采样数").range(1, 64).defaultValue(16).build())
 *     .addParameter(new EnumKnob.Builder("filter_type")
 *         .options("HARD", "PCF", "PCSS").defaultIndex(1).build())
 *     .addParameter(new BoolKnob.Builder("enable_normal_mapping")
 *         .defaultValue(true).build())
 *     .build();
 *
 * // 每帧渲染时批量应用参数到 GPU
 * shadowConfig.applyTo(renderContext);
 * }</pre>
 *
 * @see ParameterKnob
 * @see ParameterRegistry
 * @see PipelineNode
 * @since 6.0.0
 */
public final class ShaderParameterConfig {

    // ==================== 实例字段 ====================

    /** 配置唯一标识符 */
    private final String id;

    /** 配置显示名称 */
    private final String displayName;

    /** 绑定的 PipelineNode ID（可能为 null） */
    private final String boundNodeId;

    /**
     * 参数映射表（ID → ParameterKnob）
     * <p>
     * 使用 ConcurrentHashMap 支持并发读写。
     * 渲染线程读取参数值，UI 线程修改参数值。
     */
    private final ConcurrentHashMap<String, ParameterKnob<?>> parameterMap;

    /**
     * 脏标记集合
     * <p>
     * 记录自上次 applyTo() 之后哪些参数发生了变化。
     * 用于优化 GPU 上传：仅更新变化的 uniform。
     */
    private final Set<String> dirtyFlags;

    // ==================== 私有构造函数 ====================

    /**
     * 私有构造函数
     *
     * @param builder 构建器实例
     */
    private ShaderParameterConfig(Builder builder) {
        this.id = builder.id;
        this.displayName = builder.displayName != null ? builder.id : builder.displayName;
        this.boundNodeId = builder.boundNodeId;
        this.parameterMap = new ConcurrentHashMap<>(builder.initialCapacity);
        this.dirtyFlags = Collections.newSetFromMap(new ConcurrentHashMap<>());

        // 添加所有预声明的参数
        for (ParameterKnob<?> knob : builder.parameters) {
            addParameterInternal(knob);
        }
    }

    // ==================== 核心标识方法 ====================

    /**
     * 获取配置唯一标识符
     *
     * 【返回值】
     * @return String - 配置 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取配置显示名称
     *
     * 【返回值】
     * @return String - 显示名称
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 获取绑定的节点 ID
     *
     * 【返回值】
     * @return String - 节点 ID；未绑定则返回 null
     */
    public String getBoundNodeId() {
        return boundNodeId;
    }

    // ==================== 参数管理方法 ====================

    /**
     * 添加参数到此配置容器
     * <p>
     * 添加后会自动注册变更监听器以维护脏标记。
     *
     * 【方法参数】
     * @param knob ParameterKnob&lt;?&gt; - 要添加的参数实例
     *
     * @throws NullPointerException     如果 knob 为 null
     * @throws IllegalStateException    如果相同 ID 的参数已存在
     */
    public void addParameter(ParameterKnob<?> knob) {
        Objects.requireNonNull(knob, "参数不能为 null");
        addParameterInternal(knob);
    }

    /**
     * 内部添加方法（构造函数和公共 API 共用）
     */
    private void addParameterInternal(ParameterKnob<?> knob) {
        ParameterKnob<?> existing = parameterMap.putIfAbsent(knob.getId(), knob);
        if (existing != null) {
            throw new IllegalStateException(
                    String.format("参数 '%s' 已存在于配置 '%s' 中", knob.getId(), id));
        }

        // 注册变更监听器以自动标记脏数据
        final String knobId = knob.getId();
        knob.addChangeListener((k, oldVal, newVal) -> dirtyFlags.add(knobId));

        // 初始时标记为脏（确保首次 apply 会上传）
        dirtyFlags.add(knobId);
    }

    /**
     * 移除指定参数
     *
     * 【方法参数】
     * @param paramId String - 要移除的参数 ID
     *
     * @return ParameterKnob&lt;?&gt; - 被移除的参数；不存在则返回 null
     */
    public ParameterKnob<?> removeParameter(String paramId) {
        ParameterKnob<?> removed = parameterMap.remove(paramId);
        if (removed != null) {
            dirtyFlags.remove(paramId);
        }
        return removed;
    }

    /**
     * 获取指定参数
     *
     * 【方法参数】
     * @param paramId String - 参数 ID
     *
     * 【返回值】
     * @return ParameterKnob&lt;?&gt; - 参数实例；不存在则返回 null
     */
    public ParameterKnob<?> getParameter(String paramId) {
        return parameterMap.get(paramId);
    }

    /**
     * 类型安全地获取参数
     *
     * 【方法参数】
     * @param paramId String    - 参数 ID
     * @param type    Class&lt;T&gt; - 期望的类型
     *
     * @return T - 类型匹配的参数；不匹配或不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T extends ParameterKnob<?>> T getParameter(String paramId, Class<T> type) {
        ParameterKnob<?> knob = parameterMap.get(paramId);
        if (knob != null && type.isInstance(knob)) {
            return (T) knob;
        }
        return null;
    }

    /**
     * 获取所有参数
     *
     * 【返回值】
     * @return Collection&lt;ParameterKnob&lt;?&gt;&gt; - 参数集合视图
     */
    public Collection<ParameterKnob<?>> getAllParameters() {
        return Collections.unmodifiableCollection(parameterMap.values());
    }

    /**
     * 获取参数数量
     *
     * 【返回值】
     * @return int - 此容器中的参数总数
     */
    public int getParameterCount() {
        return parameterMap.size();
    }

    /**
     * 检查是否包含指定参数
     *
     * @param paramId String - 参数 ID
     * @return boolean - true 表示包含
     */
    public boolean hasParameter(String paramId) {
        return parameterMap.containsKey(paramId);
    }

    // ==================== GPU 应用方法（核心功能）====================

    /**
     * 批量将所有参数应用到渲染上下文（GPU Uniforms）
     * <p>
     * 这是核心方法，每帧在对应 PipelineNode.execute() 之前调用。
     * 遍历容器中所有参数，将其当前值写入对应的 GPU Uniform 变量。
     * <p>
     * <b>性能优化</b>：
     * <ul>
     *   <li>仅上传脏标记中的参数（上次 apply 后发生过变化的）</li>
     *   <li>apply 完成后清除脏标记</li>
     *   <li>无锁读取 volatile 值</li>
     * </ul>
     *
     * 【方法参数】
     * @param context RenderContext - 当前帧的渲染上下文，用于获取 Vulkan 设备和命令缓冲区
     *
     * @return int - 本次实际更新（上传到 GPU）的参数数量
     */
    public int applyTo(RenderContext context) {
        Objects.requireNonNull(context, "RenderContext 不能为 null");

        int updatedCount = 0;

        if (dirtyFlags.isEmpty()) {
            // 无脏数据，跳过整个流程
            return 0;
        }

        // 复制脏标记快照（防止遍历时并发修改）
        Set<String> dirtySnapshot = Set.copyOf(dirtyFlags);

        for (String paramId : dirtySnapshot) {
            ParameterKnob<?> knob = parameterMap.get(paramId);
            if (knob == null) continue;

            try {
                // 根据参数类型分发到具体的 GPU 上传逻辑
                boolean uploaded = uploadUniform(context, paramId, knob);
                if (uploaded) {
                    updatedCount++;
                }
            } catch (Exception e) {
                // 单个参数上传失败不应阻断其他参数
                Thread.currentThread().getUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(), e);
            }
        }

        // 清除已处理的脏标记
        dirtyFlags.removeAll(dirtySnapshot);

        return updatedCount;
    }

    /**
     * 强制上传所有参数到 GPU（忽略脏标记）
     * <p>
     * 用于初始化、Shader 重编译等需要完整同步的场景。
     *
     * 【方法参数】
     * @param context RenderContext - 渲染上下文
     *
     * @return int - 上传的参数数量
     */
    public int forceApplyAll(RenderContext context) {
        Objects.requireNonNull(context, "RenderContext 不能为 null");

        // 先将所有参数标记为脏
        dirtyFlags.addAll(parameterMap.keySet());

        // 然后执行常规 apply
        return applyTo(context);
    }

    /**
     * 上传单个参数到 GPU Uniform
     * <p>
     * 根据 KnobType 分发到不同的上传策略。
     * 注意：此方法的具体 GPU 调用取决于底层图形 API 实现（Vulkan/OpenGL），
     * 这里定义的是接口契约。实际实现需根据项目的图形后端进行适配。
     *
     * 【方法参数】
     * @param context RenderContext       - 渲染上下文
     * @param uniformName String          - Uniform 变量名（使用参数 ID）
     * @param knob     ParameterKnob&lt;?&gt; - 参数旋钮实例
     *
     * 【返回值】
     * @return boolean - true 表示成功上传，false 表示跳过或失败
     */
    private boolean uploadUniform(RenderContext context, String uniformName, ParameterKnob<?> knob) {
        // 根据参数类型执行不同的 GPU 上传操作
        switch (knob.getType()) {
            case FLOAT -> {
                float value = ((FloatKnob) knob).getRawValue();
                // TODO: 调用 Vulkan/OpenGL 后端设置 float uniform
                // 示例：vkCmdPushConstants(commandBuffer, layout, stage, offset, FLOAT_SIZE, &value);
                return true;
            }

            case INT -> {
                int value = ((IntKnob) knob).getRawValue();
                // TODO: 调用后端设置 int uniform
                return true;
            }

            case BOOL -> {
                boolean value = ((BoolKnob) knob).getRawValue();
                // TODO: 调用后端设置 bool uniform（通常作为 int 0/1 上传）
                return true;
            }

            case ENUM -> {
                int index = ((EnumKnob) knob).getCurrentIndex();
                // TODO: 枚举作为 int 索引上传到 GPU
                return true;
            }

            case VEC2, VEC3, COLOR -> {
                // 向量类型暂不支持基础实现
                // 可通过扩展实现
                return false;
            }

            default -> {
                return false;
            }
        }
    }

    // ==================== 脏标记管理 ====================

    /**
     * 获取当前脏标记集合的副本
     * <p>
     * 用于调试或 UI 显示"待同步"状态。
     *
     * 【返回值】
     * @return Set&lt;String&gt; - 发生变化但尚未 apply 的参数 ID 集合
     */
    public Set<String> getDirtyParameters() {
        return Set.copyOf(dirtyFlags);
    }

    /**
     * 获取脏参数数量
     *
     * 【返回值】
     * @return int - 待更新的参数数量
     */
    public int getDirtyCount() {
        return dirtyFlags.size();
    }

    /**
     * 检查是否有任何脏参数
     *
     * 【返回值】
     * @return boolean - true 表示存在待更新的参数
     */
    public boolean hasDirtyParameters() {
        return !dirtyFlags.isEmpty();
    }

    /**
     * 手动将所有参数标记为脏
     * <p>
     * 用于外部强制刷新场景。
     */
    public void markAllDirty() {
        dirtyFlags.addAll(parameterMap.keySet());
    }

    /**
     * 清除所有脏标记（不执行上传）
     * <p>
     * 危险操作！可能导致 GPU 与 CPU 状态不一致。
     * 仅用于特殊同步场景。
     */
    public void clearDirtyFlags() {
        dirtyFlags.clear();
    }

    // ==================== 批量操作方法 ====================

    /**
     * 重置所有参数为默认值
     */
    public void resetAllToDefault() {
        for (ParameterKnob<?> knob : parameterMap.values()) {
            knob.resetToDefault();
        }
    }

    /**
     * 从 ParameterRegistry 批量导入参数
     * <p>
     * 按 ID 匹配从全局注册表中查找并添加参数。
     *
     * 【方法参数】
     * @param registry ParameterRegistry - 全局注册表
     * @param ids      String...         - 要导入的参数 ID 列表
     *
     * @return int - 成功导入的数量
     */
    public int importFromRegistry(ParameterRegistry registry, String... ids) {
        int count = 0;
        for (String id : ids) {
            ParameterKnob<?> knob = registry.get(id);
            if (knob != null && !parameterMap.containsKey(id)) {
                addParameterInternal(knob);
                count++;
            }
        }
        return count;
    }

    /**
     * 将此容器中的所有参数注册到全局注册表
     *
     * 【方法参数】
     * @param registry ParameterRegistry - 目标注册表
     *
     * @return int - 成功注册的数量
     */
    public int registerToGlobal(ParameterRegistry registry) {
        int count = 0;
        for (ParameterKnob<?> knob : parameterMap.values()) {
            try {
                registry.register(knob);
                count++;
            } catch (IllegalStateException e) {
                // 已存在则跳过
            }
        }
        return count;
    }

    // ==================== Object 方法重写 ====================

    @Override
    public String toString() {
        return String.format("ShaderParameterConfig{id='%s', name='%s', node='%s', params=%d, dirty=%d}",
                id, displayName,
                boundNodeId != null ? boundNodeId : "(unbound)",
                parameterMap.size(),
                dirtyFlags.size());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ShaderParameterConfig other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    // ==================== Builder 模式 ====================

    /**
     * ShaderParameterConfig 构建器
     * <p>
     * 使用 Builder 模式灵活构建配置容器实例，
     * 支持绑定 PipelineNode 和预添加参数列表。
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * ShaderParameterConfig config = new ShaderParameterConfig.Builder("shadow_pass_config")
     *     .displayName("阴影通道配置")
     *     .bindNode("shadow_map_node")
     *     .initialCapacity(16)
     *     .addParameter(normalStrengthKnob)
     *     .addParameter(pcfSamplesKnob)
     *     .addParameter(filterTypeKnob)
     *     .addParameter(enableNormalMappingKnob)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        // ---------- 必填字段 ----------
        private final String id;

        // ---------- 可选字段 ----------
        private String displayName;
        private String boundNodeId;
        private int initialCapacity = 16;
        private final List<ParameterKnob<?>> parameters = new ArrayList<>();

        /**
         * 构造函数 - 设置配置 ID（必填）
         *
         * @param id String - 配置唯一标识符
         */
        public Builder(String id) {
            this.id = Objects.requireNonNull(id, "配置 ID 不能为 null");
        }

        /**
         * 设置显示名称
         *
         * @param name String - 显示名称
         * @return this
         */
        public Builder displayName(String name) {
            this.displayName = name;
            return this;
        }

        /**
         * 绑定到指定的 PipelineNode
         * <p>
         * 绑定后可通过 getBoundNodeId() 查询关联节点。
         *
         * @param nodeId String - PipelineNode 的 ID
         * @return this
         */
        public Builder bindNode(String nodeId) {
            this.boundNodeId = nodeId;
            return this;
        }

        /**
         * 绑定到 PipelineNode 实例（自动提取 ID）
         *
         * @param node PipelineNode - 目标节点实例
         * @return this
         */
        public Builder bindNode(PipelineNode node) {
            if (node != null) {
                this.boundNodeId = node.getId();
            }
            return this;
        }

        /**
         * 设置初始容量（性能优化）
         * <p>
     * 如果已知参数数量，预设可减少 HashMap 扩容开销。
         *
         * @param capacity int - 预期参数数量（必须 > 0）
         * @return this
         */
        public Builder initialCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("初始容量必须大于 0: " + capacity);
            }
            this.initialCapacity = capacity;
            return this;
        }

        /**
         * 添加一个参数到此配置容器
         *
         * @param knob ParameterKnob&lt;?&gt; - 参数实例
         * @return this
         */
        public Builder addParameter(ParameterKnob<?> knob) {
            if (knob != null) {
                parameters.add(knob);
            }
            return this;
        }

        /**
         * 构建 ShaderParameterConfig 实例
         *
         * @return ShaderParameterConfig - 构建好的配置容器
         */
        public ShaderParameterConfig build() {
            return new ShaderParameterConfig(this);
        }
    }
}
