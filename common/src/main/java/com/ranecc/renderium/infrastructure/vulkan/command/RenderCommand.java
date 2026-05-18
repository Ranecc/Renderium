// Renderium - 渲染命令基类接口
// 定义所有渲染操作的抽象接口，是 CommandBuffer 的基本单元
// 参考: Vulkan Command Buffer / DX12 Command List

package com.ranecc.renderium.infrastructure.vulkan.command;

import com.ranecc.renderium.feature.pipeline.core.RenderPipeline;

/**
 * 渲染命令基类接口
 * <p>
 * 所有渲染操作（绘制、计算、拷贝、清除等）的统一抽象。
 * 这是 "GL 状态机 → 现代命令流" 架构中，进入 CommandBuffer 的最小单元。
 * <p>
 * 设计原则：
 * <ul>
 *   <li><b>不可变性</b>: 命令一旦创建就不能修改</li>
 *   <li><b>自包含</b>: 命令携带执行所需的所有信息</li>
 *   <li><b>类型安全</b>: 通过子类型区分不同操作</li>
 * </ul>
 *
 * <h3>命令生命周期：</h3>
 * <pre>
 * 1. 创建: MockGL11 拦截 GL 调用时生成
 * 2. 录制: 写入 CommandBuffer
 * 3. 提交: 由渲染线程消费并翻译为底层 API 调用
 * </pre>
 *
 * @see DrawCommand
 * @see CommandBuffer
 */
public interface RenderCommand {

    /**
     * 获取命令关联的渲染管线状态
     *
     * @return 不可变的 RenderPipeline 对象
     */
    RenderPipeline getPipeline();

    /**
     * 获取命令类型枚举
     *
     * @return 命令类型
     */
    CommandType getType();

    // ==================== 命令类型枚举 ====================

    /**
     * 渲染命令类型
     * 用于快速类型判断和分发
     */
    enum CommandType {
        /** 绘制命令 (DrawArrays / DrawElements) */
        DRAW,

        /** 计算着色器分派 */
        COMPUTE,

        /** 缓冲区/纹理拷贝 */
        COPY,

        /** 渲染目标清除 */
        CLEAR,

        /** 管线屏障 (同步点) */
        BARRIER,

        /** 开始/结束渲染通道 */
        RENDER_PASS,

        /** 绑定资源集 */
        BIND_RESOURCES
    }
}
