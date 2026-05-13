package com.ranecc.renderium.domain.service.algorithm;
import com.ranecc.renderium.feature.pipeline.strategy.BfsInput;
import com.ranecc.renderium.feature.pipeline.strategy.BfsInput;

/**
 * BFS 遮挡剔除 FFI 适配器专用接口
 *
 * <p>扩展 {@link FFIAdapter} 接口，添加 BFS 算法特有的方法签名。
 *
 * <h3>方法契约</h3>
 * <ul>
 *   <li>{@code createContext()}: 创建 Native 上下文（返回句柄）</li>
 *   <li>{@code compute()}: 执行可见性计算（输入相机+区块数据）</li>
 *   <li>{@code destroyContext()}: 释放 Native 资源</li>
 * </ul>
 *
 * @see com.ranecc.renderium.infrastructure.nativeLib.binding.BfsOcclusionFFIAdapter
 * @since 1.1.0
 */
public interface BfsFFIAdapter extends FFIAdapter<BfsInput> {

    /**
     * 创建 BFS 计算上下文
     *
     * @param maxChunks 最大分块数 (建议: 64-256)
     * @param maxDepth 最大递归深度 (建议: 8-16)
     * @param cullDistance 剔除距离（世界单位）
     * @return 上下文句柄（long 类型，0 表示失败）
     */
    long createContext(int maxChunks, int maxDepth, float cullDistance);

    /**
     * 执行 BFS 可见性计算
     *
     * @param context 上下文句柄（由 {@link #createContext} 返回）
     * @param input    BFS 输入数据（包含相机位置、视锥矩阵、区块数据）
     * @return 更新后的 BfsInput（结果可能直接写入缓冲区）
     */
    @Override
    BfsInput execute(BfsInput input);

    /**
     * 销毁 BFS 上下文并释放 Native 资源
     *
     * @param context 要销毁的上下文句柄
     */
    void destroyContext(long context);
}
