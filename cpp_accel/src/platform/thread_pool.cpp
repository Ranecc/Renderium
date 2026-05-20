// ============================================================
// Renderium Accelerator - 安全线程池 (崩溃隔离)
// ============================================================
// 为 CPU 计算任务提供隔离执行环境。
//
// 崩溃隔离策略:
//   1. 每个任务在独立 std::thread 中执行
//   2. C++ 信号处理 (SIGSEGV/SIGABRT) 通过 sigsetjmp/siglongjmp 恢复
//   3. 心跳检测 (atomic timestamp) 供 Java 侧轮询
//   4. 退化标记: 崩溃后标记 degraded=true，所有后续任务静默跳过
// ============================================================

#include <atomic>
#include <thread>
#include <functional>
#include <chrono>
#include <cstring>
#include <csetjmp>
#include <csignal>

#ifdef _WIN32
    #include <windows.h>
#endif

#include "accel_config.h"
#include "platform_abstraction.h"

namespace renderium {
namespace accel {
namespace thread_safe {

// ==================== 全局状态 ====================

/// 心跳时间戳（纳秒，std::atomic 确保 Java 侧读取可见性）
static std::atomic<uint64_t> g_heartbeatNs{0};

/// 退化标志：true 表示发生过崩溃
static std::atomic<bool> g_degraded{false};

/// 当前作业状态
static std::atomic<int> g_taskState{0}; // 0=IDLE, 1=RUNNING, 2=DONE, 3=FAILED

/// 正在运行的任务线程
static std::thread* g_activeThread = nullptr;

/// 信号跳转缓冲（用于崩溃恢复）
static thread_local sigjmp_buf g_signalJmpBuf;

// ==================== 信号处理 ====================

/// 信号处理函数：捕获 SIGSEGV/SIGABRT 后恢复执行
static void signalHandler(int sig) {
    // 防止递归信号
    std::signal(sig, SIG_DFL);
    g_degraded.store(true, std::memory_order_release);
    g_taskState.store(3, std::memory_order_release); // FAILED
    // 跳回线程入口处的 sigsetjmp
    siglongjmp(g_signalJmpBuf, 1);
}

/// 安装信号处理器到当前线程
static void installSignalHandlers() {
    struct sigaction sa;
    std::memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signalHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_NODEFER; // 防止同一个信号阻塞
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
#ifdef SIGBUS
    sigaction(SIGBUS, &sa, nullptr);
#endif
}

// ==================== 任务执行 ====================

/// 在安全线程中执行的任务函数
/// @tparam Fn 可调用对象类型（void() 签名）
template<typename Fn>
static void safeThreadEntry(Fn&& task) {
    installSignalHandlers();

    if (sigsetjmp(g_signalJmpBuf, 1) == 0) {
        // 正常路径
        task();
        g_taskState.store(2, std::memory_order_release); // DONE
    } else {
        // 从信号处理返回 — 发生了崩溃
        g_taskState.store(3, std::memory_order_release); // FAILED
        g_degraded.store(true, std::memory_order_release);
    }
}

/// 生成本地时间戳（纳秒）作为心跳信号
static uint64_t nowNs() {
    return static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
}

// ==================== 公共 API ====================

/// 在安全隔离线程中执行一个函数
/// @param task  要执行的函数 (void() 签名)
/// @return 0=成功启动, 负值=错误
int executeInSafeThread(std::function<void()> task) {
    if (g_degraded.load(std::memory_order_acquire)) {
        return -1; // 已降级，拒绝新任务
    }

    // 等待前一个线程结束
    if (g_activeThread != nullptr) {
        if (g_activeThread->joinable()) {
            g_activeThread->join();
        }
        delete g_activeThread;
        g_activeThread = nullptr;
    }

    g_taskState.store(1, std::memory_order_release); // RUNNING

    g_activeThread = new std::thread([task = std::move(task)]() {
        // 心跳线程：每 100ms 更新时间戳
        std::atomic<bool> running{true};
        std::thread heartbeatThread([&running]() {
            while (running.load(std::memory_order_relaxed)) {
                g_heartbeatNs.store(nowNs(), std::memory_order_release);
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });

        // 执行任务（在信号保护下）
        safeThreadEntry(std::move(task));

        // 停止心跳线程
        running.store(false, std::memory_order_relaxed);
        if (heartbeatThread.joinable()) {
            heartbeatThread.join();
        }
    });

    return 0;
}

/// 查询心跳时间戳
uint64_t heartbeatNs() {
    return g_heartbeatNs.load(std::memory_order_acquire);
}

/// 查询是否发生崩溃
bool isDegraded() {
    return g_degraded.load(std::memory_order_acquire);
}

/// 重置退化状态（用于重新初始化后）
void resetDegraded() {
    g_degraded.store(false, std::memory_order_release);
    g_taskState.store(0, std::memory_order_release); // IDLE
}

/// 获取当前任务状态
int taskState() {
    return g_taskState.load(std::memory_order_acquire);
}

/// 关闭并清理线程
void shutdown() {
    if (g_activeThread != nullptr) {
        if (g_activeThread->joinable()) {
            g_activeThread->join();
        }
        delete g_activeThread;
        g_activeThread = nullptr;
    }
    g_heartbeatNs.store(0, std::memory_order_release);
    g_taskState.store(0, std::memory_order_release);
}

} // namespace thread_safe
} // namespace accel
} // namespace renderium
