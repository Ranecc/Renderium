// Renderium - 搜索索引占位接口
// 后续 Task 将完善此接口的实现

package com.renderium.config.structure;

/**
 * 配置选项搜索索引。
 *
 * <p>用于支持设置界面中的文本搜索功能，
 * 允许用户通过关键词快速定位目标选项。
 *
 * <p><b>注意</b>：此为占位接口，完整实现将在后续 Task 中提供。
 * 当前仅定义最小必要的方法签名以支持编译。
 *
 * @author Renderium Team
 * @since 5.0.0
 * @see RendererPage#registerTextSources(SearchIndex, Object)
 */
public interface SearchIndex {

    /**
     * 注册一个可搜索的文本源。
     *
     * @param source 文本源对象（后续将定义为 TextSource 类型）
     */
    void register(Object source);
}
