// ============================================================
// Renderium Accelerator - 安全线程池接口
// ============================================================
#pragma once

#include <functional>
#include <cstdint>

namespace renderium {
namespace accel {
namespace thread_safe {

/// 在安全隔离线程中执行一个函数
int executeInSafeThread(std::function<void()> task);

/// 查询心跳时间戳（纳秒）
uint64_t heartbeatNs();

/// 查询是否发生过崩溃
bool isDegraded();

/// 获取当前任务状态（0=IDLE, 1=RUNNING, 2=DONE, 3=FAILED）
int taskState();

/// 重置退化状态
void resetDegraded();

/// 关闭并清理
void shutdown();

} // namespace thread_safe
} // namespace accel
} // namespace renderium
