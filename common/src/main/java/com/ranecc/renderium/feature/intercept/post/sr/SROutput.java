package com.ranecc.renderium.feature.intercept.post.sr;

/**
 * 超分辨率输出结果
 * 封装超分辨率处理的输出状态和结果数据
 */
public class SROutput {
    private final boolean success;
    private final String message;

    public SROutput(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /** 处理是否成功 */
    public boolean isSuccess() { return success; }

    /** 结果消息或错误信息 */
    public String getMessage() { return message; }
}
