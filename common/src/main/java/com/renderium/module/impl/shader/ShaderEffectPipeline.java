// Renderium - 光影模块
// 着色器效果管线 - 管理多个后处理效果的执行顺序

package com.renderium.module.impl.shader;

import com.renderium.module.ModuleContext;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 着色器效果管线
 * <p>
 * 管理后处理效果链的构建和执行。
 * TODO: 完整实现效果管线逻辑。
 *
 * @author Renderium Team
 * @since 2.0.0
 */
public class ShaderEffectPipeline implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ShaderEffectPipeline.class.getName());

    private final ModuleContext context;
    private final List<ShaderEffect> effects = new ArrayList<>();

    public ShaderEffectPipeline(ModuleContext context) {
        this.context = context;
    }

    /**
     * 构建效果链
     */
    public void buildEffectChain(RGBPackManifest manifest, List<CompiledShader> shaders) {
        // TODO: 根据清单和编译后的着色器创建 ShaderEffect 实例
        effects.clear();
    }

    /** 获取所有效果 */
    public List<ShaderEffect> getEffects() { return List.copyOf(effects); }

    /** 清除效果链 */
    public void clear() { effects.clear(); }

    /** 启用管线 */
    public void enable() {}

    /** 禁用管线 */
    public void disable() { effects.clear(); }

    @Override
    public void close() { disable(); }
}
