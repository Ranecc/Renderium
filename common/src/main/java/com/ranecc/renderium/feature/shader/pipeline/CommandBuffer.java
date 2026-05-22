package com.ranecc.renderium.feature.shader.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 命令缓冲区 - 记录渲染命令队列
 * <p>
 * 支持 begin/end/submit 生命周期，可在录制期间收集命令，
 * 提交后清空缓冲区。用于解耦命令生产与消费。
 * </p>
 */
public class CommandBuffer {

    /** 存储录制中的命令对象 */
    private final List<Object> commands = new ArrayList<>();

    /** 命令缓冲区状态枚举 */
    public enum State {
        RECORDING,
        EXECUTING,
        IDLE
    }

    /** 当前状态 */
    private State state = State.IDLE;

    /** 是否处于录制状态 */
    private boolean recording;

    /**
     * 获取当前状态
     *
     * @return 当前状态枚举
     */
    public State getState() {
        return state;
    }

    /**
     * 开始录制：清空历史命令并置 recording = true
     */
    public void begin() {
        recording = true;
        state = State.RECORDING;
        commands.clear();
    }

    /**
     * 结束录制：置 recording = false，保留已收集的命令
     */
    public void end() {
        recording = false;
        state = State.EXECUTING;
    }

    /**
     * 提交命令：清空缓冲区，供消费方处理
     */
    public void submit() {
        commands.clear();
    }

    /**
     * 录制一条命令（仅在录制状态下有效）
     *
     * @param command 命令对象
     */
    public void record(Object command) {
        if (recording) {
            commands.add(command);
        }
    }

    /**
     * 获取已录制的命令数量
     *
     * @return 命令条数
     */
    public int getCommandCount() {
        return commands.size();
    }

    /**
     * 按索引获取已录制的命令
     *
     * @param index 索引
     * @return 命令对象
     */
    public Object getCommand(int index) {
        return commands.get(index);
    }
}
