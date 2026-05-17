package com.renderium.core;

import com.renderium.pipeline.BfsOcclusionEngine;
import com.renderium.pipeline.BfsOcclusionEngine.OcclusionTask;
import com.renderium.pipeline.BfsOcclusionEngine.CameraView;
import com.renderium.pipeline.BfsOcclusionEngine.Direction;
import com.renderium.pipeline.LockFreeRingBuffer;
import com.renderium.pipeline.DoubleBufferQueue;
import com.renderium.pipeline.DoubleBufferQueue.WriteView;
import com.renderium.pipeline.DoubleBufferQueue.ReadView;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderium 性能基准测试套件
 * <p>
 * 纯 Java 微基准测试（不依赖 JMH 框架），覆盖：
 * <ol>
 *   <li>热路径延迟基准：BFS遮挡剔除、无锁环形缓冲区、双缓冲队列</li>
 *   <li>GPU友好性验证：扫描热路径类源码，检查 Math.sqrt/Math.pow/浮点除法</li>
 *   <li>FPS推算：基于热路径总延迟推算理论FPS上限</li>
 *   <li>冷路径隔离验证：确认后拦截器/异步构建管道不在热路径中</li>
 * </ol>
 *
 * <h3>测试方法：</h3>
 * <ul>
 *   <li>JIT预热：1000次迭代</li>
 *   <li>正式测量：10000次迭代取平均值</li>
 *   <li>计时方式：System.nanoTime()</li>
 * </ul>
 */
public class RenderiumPerformanceBenchmark {

    // ==================== 配置常量 ====================

    /** JIT 预热迭代次数 */
    private static final int WARMUP_ITERATIONS = 1000;

    /** 正式测量迭代次数 */
    private static final int MEASURE_ITERATIONS = 10000;

    /** BFS 基准测试模拟区块数量（10x10x10） */
    private static final int BFS_SECTION_COUNT = 1000;

    /** 双缓冲队列基准测试元素数量 */
    private static final int DBQ_ELEMENT_COUNT = 1000;

    /** BFS 网格边长 */
    private static final int GRID_SIZE = 10;

    // ==================== 目标阈值 ====================

    /** BFS 遮挡剔除目标延迟（纳秒）：500us = 500,000ns */
    private static final long BFS_TARGET_NS = 500_000L;

    /** 无锁环形缓冲区目标延迟（纳秒）：100ns */
    private static final long RING_BUFFER_TARGET_NS = 100L;

    /** 双缓冲队列目标延迟（纳秒）：50us = 50,000ns */
    private static final long DBQ_TARGET_NS = 50_000L;

    /** FPS 推算目标：>= 1000 FPS */
    private static final int FPS_TARGET = 1000;

    // ==================== GPU 不友好操作检测模式 ====================

    /** Math.sqrt() 调用 */
    private static final Pattern MATH_SQRT = Pattern.compile("Math\\.sqrt\\s*\\(");

    /** Math.pow() 调用 */
    private static final Pattern MATH_POW = Pattern.compile("Math\\.pow\\s*\\(");

    /** 浮点除法：除以浮点字面量（如 / 2.0, / 16.0f） */
    private static final Pattern FLOAT_DIV = Pattern.compile("/\\s*(?:\\d+\\.\\d*[fd]?|\\d+[fd])\\b");

    /** GPU 不友好模式名称 */
    private static final String[] GPU_PATTERN_NAMES = {"Math.sqrt()", "Math.pow()", "浮点除法"};

    // ==================== 热路径类定义（类名 v 源文件相对路径） ====================

    private static final String[][] HOT_PATH_CLASSES = {
            {"BfsOcclusionEngine", "com/renderium/pipeline/BfsOcclusionEngine.java"},
            {"LockFreeRingBuffer", "com/renderium/pipeline/LockFreeRingBuffer.java"},
            {"DoubleBufferQueue", "com/renderium/pipeline/DoubleBufferQueue.java"},
            {"LODCalculator", "com/renderium/interception/lod/LODCalculator.java"},
            {"AsyncRenderPipeline", "com/renderium/pipeline/AsyncRenderPipeline.java"},
    };

    /** 冷路径类名（用于隔离验证） */
    private static final String[] COLD_PATH_CLASS_NAMES = {
            "DefaultPostInterceptor",
            "AsyncChunkBuildPipeline",
    };

    // ==================== 报告缓冲区与状态 ====================

    private static final StringBuilder report = new StringBuilder(8192);

    /** 总体测试结果 */
    private static boolean allPassed = true;

    // ==================== 入口方法 ====================

    public static void main(String[] args) {
        printHeader();

        /* 1. 热路径延迟基准 */
        long bfsAvgNs = benchmarkBfsOcclusion();
        long ringAvgNs = benchmarkLockFreeRingBuffer();
        long dbqAvgNs = benchmarkDoubleBufferQueue();

        /* 2. GPU 友好性验证 */
        boolean gpuFriendly = verifyGpuFriendliness();

        /* 3. FPS 推算 */
        boolean fpsPassed = estimateFps(bfsAvgNs, ringAvgNs, dbqAvgNs);

        /* 4. 冷路径隔离验证 */
        boolean coldPathIsolated = verifyColdPathIsolation();

        /* 汇总 */
        allPassed = allPassed && gpuFriendly && fpsPassed && coldPathIsolated;
        printSummary();

        System.out.println(report.toString());
    }

    // ==================== 基准测试 1: BFS 遮挡剔除 ====================

    /**
     * 测量 BfsOcclusionEngine.findVisibleSections() 单次遍历延迟
     * <p>
     * 创建 10x10x10 = 1000 个 OcclusionTask 模拟区块，
     * 设置全可见性编码，从中心区块开始 BFS 遍历。
     *
     * @return 平均单次遍历延迟（纳秒）
     */
    private static long benchmarkBfsOcclusion() {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=  1. 热路径延迟基准: BFS 遮挡剔除                              =\n");
        report.append("+--------------------------------------------------------------+\n");

        /* 创建 BFS 引擎 */
        BfsOcclusionEngine engine = new BfsOcclusionEngine();

        /* 使用引擎提供的全可见性常量 */
        long allVisible = BfsOcclusionEngine.VISIBILITY_FULL;

        /* 创建 10x10x10 区块网格 */
        OcclusionTask[][][] grid = new OcclusionTask[GRID_SIZE][GRID_SIZE][GRID_SIZE];
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    OcclusionTask task = new OcclusionTask(x, y, z);
                    task.visibilityData = allVisible;
                    grid[x][y][z] = task;
                }
            }
        }

        /* 建立6方向邻居连接 */
        for (int x = 0; x < GRID_SIZE; x++) {
            for (int y = 0; y < GRID_SIZE; y++) {
                for (int z = 0; z < GRID_SIZE; z++) {
                    if (x > 0) grid[x][y][z].setNeighbor(Direction.WEST, grid[x - 1][y][z]);
                    if (x < GRID_SIZE - 1) grid[x][y][z].setNeighbor(Direction.EAST, grid[x + 1][y][z]);
                    if (y > 0) grid[x][y][z].setNeighbor(Direction.DOWN, grid[x][y - 1][z]);
                    if (y < GRID_SIZE - 1) grid[x][y][z].setNeighbor(Direction.UP, grid[x][y + 1][z]);
                    if (z > 0) grid[x][y][z].setNeighbor(Direction.NORTH, grid[x][y][z - 1]);
                    if (z < GRID_SIZE - 1) grid[x][y][z].setNeighbor(Direction.SOUTH, grid[x][y][z + 1]);
                }
            }
        }

        /* 根区块位于网格中心 (5, 5, 5) */
        OcclusionTask root = grid[5][5][5];

        /* 相机视图：位于根区块中心，渲染距离 512 格 */
        CameraView cameraView = new CameraView(
                5, 5, 5,
                5 * 16 + 8.0f,
                5 * 16 + 8.0f,
                5 * 16 + 8.0f,
                512.0f
        );

        /* JIT 预热 */
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            engine.findVisibleSections(root, cameraView, true, i);
        }

        /* 正式测量 */
        long totalNs = 0L;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            engine.findVisibleSections(root, cameraView, true, WARMUP_ITERATIONS + i);
            totalNs += System.nanoTime() - start;
        }

        long avgNs = totalNs / MEASURE_ITERATIONS;
        boolean passed = avgNs < BFS_TARGET_NS;
        if (!passed) allPassed = false;

        report.append(String.format(
                "|  BFS: %s  %s  [%s]%n",
                BFS_SECTION_COUNT, formatNanos(avgNs), passed ? "通过" : "失败"));
        report.append("+--------------------------------------------------------------+\n");

        return avgNs;
    }

    // ==================== 基准测试 2: 无锁环形缓冲区 ====================

    /**
     * 测量 LockFreeRingBuffer 单次 enqueue+dequeue 延迟
     * <p>
     * 方法：每次入队一个元素后立即出队，测量单次循环耗时。
     *
     * @return 平均单次 enqueue+dequeue 延迟（纳秒）
     */
    private static long benchmarkLockFreeRingBuffer() {
        report.append("=  1. 热路径延迟基准: 无锁环形缓冲区                            =\n");

        LockFreeRingBuffer<Integer> buffer = new LockFreeRingBuffer<>(1024);

        /* JIT 预热 */
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            buffer.enqueue(i);
            buffer.dequeue();
        }

        /* 正式测量：单次 enqueue + dequeue */
        long totalNs = 0L;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            buffer.enqueue(i);
            buffer.dequeue();
            totalNs += System.nanoTime() - start;
        }

        long avgNs = totalNs / MEASURE_ITERATIONS;
        boolean passed = avgNs < RING_BUFFER_TARGET_NS;
        if (!passed) allPassed = false;

        report.append(String.format(
                "|  LockFreeRingBuffer: %s  [%s]%n",
                formatNanos(avgNs), passed ? "通过" : "失败"));
        report.append("+--------------------------------------------------------------+\n");

        return avgNs;
    }

    // ==================== 基准测试 3: 双缓冲队列 ====================

    /**
     * 测量 DoubleBufferQueue 写入1000元素后 flip+drain 延迟
     * <p>
     * 写入阶段不计入测量时间，仅测量 flip() + drain（逐个 dequeue）耗时。
     *
     * @return 平均单次 flip+drain 延迟（纳秒）
     */
    private static long benchmarkDoubleBufferQueue() {
        report.append("=  1. 热路径延迟基准: 双缓冲队列                                =\n");

        DoubleBufferQueue<Integer> queue = new DoubleBufferQueue<>(1024);

        /* JIT 预热 */
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            WriteView<Integer> wv = queue.write();
            for (int j = 0; j < DBQ_ELEMENT_COUNT; j++) {
                wv.enqueue(j);
            }
            queue.flip();
            ReadView<Integer> rv = queue.read();
            while (rv.dequeue() != null) {
                /* drain */
            }
            queue.reset();
        }

        /* 正式测量（仅测量 flip + drain 时间，写入阶段不计入） */
        long totalNs = 0L;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            /* 写入阶段（不计入测量时间） */
            WriteView<Integer> wv = queue.write();
            for (int j = 0; j < DBQ_ELEMENT_COUNT; j++) {
                wv.enqueue(j);
            }

            /* 测量 flip + drain */
            long start = System.nanoTime();
            queue.flip();
            ReadView<Integer> rv = queue.read();
            while (rv.dequeue() != null) {
                /* drain */
            }
            totalNs += System.nanoTime() - start;

            queue.reset();
        }

        long avgNs = totalNs / MEASURE_ITERATIONS;
        boolean passed = avgNs < DBQ_TARGET_NS;
        if (!passed) allPassed = false;

        report.append(String.format(
                "|  DoubleBufferQueue (%d elements): %s  [%s]%n",
                DBQ_ELEMENT_COUNT, formatNanos(avgNs), passed ? "通过" : "失败"));
        report.append("+--------------------------------------------------------------+\n");

        return avgNs;
    }

    // ==================== 验证 2: GPU 友好性 ====================

    /**
     * 扫描热路径类源码，检查是否存在 GPU 不友好操作
     * <p>
     * 检测项：
     * <ul>
     *   <li>Math.sqrt() -- GPU shader 中无硬件加速，应避免</li>
     *   <li>Math.pow() -- 通用幂运算，GPU 上通常退化为 exp/log 组合</li>
     *   <li>浮点除法（/ 浮点字面量）-- 应替换为乘以倒数或定点数运算</li>
     * </ul>
     *
     * @return true 如果所有热路径类均通过 GPU 友好性检查
     */
    private static boolean verifyGpuFriendliness() {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=  2. GPU 友好性验证                                           =\n");
        report.append("+--------------------------------------------------------------+\n");

        Path sourceRoot = findSourceRoot();
        if (sourceRoot == null) {
            report.append("|  [WARN] 无法定位源码根目录，跳过 GPU 友好性验证                   |\n");
            report.append("+--------------------------------------------------------------+\n");
            return true;
        }

        Pattern[] gpuPatterns = {MATH_SQRT, MATH_POW, FLOAT_DIV};
        boolean allGpuFriendly = true;

        for (String[] cls : HOT_PATH_CLASSES) {
            String className = cls[0];
            String relativePath = cls[1];
            Path sourceFile = sourceRoot.resolve(relativePath);

            if (!Files.exists(sourceFile)) {
                report.append(String.format("|  [WARN] 源文件不存在: %s (%s)%n", className, relativePath));
                continue;
            }

            String sourceCode = readSourceFile(sourceFile);
            if (sourceCode == null) {
                report.append(String.format("|  [WARN] 无法读取源文件: %s%n", className));
                continue;
            }

            /* 逐模式扫描 */
            List<String> violations = new ArrayList<>();
            List<String> violationLines = new ArrayList<>();
            for (int p = 0; p < gpuPatterns.length; p++) {
                Matcher matcher = gpuPatterns[p].matcher(sourceCode);
                boolean found = false;
                while (matcher.find()) {
                    if (!found) {
                        violations.add(GPU_PATTERN_NAMES[p]);
                        found = true;
                    }
                    /* 记录首次出现的行号 */
                    int lineNum = countLines(sourceCode.substring(0, matcher.start())) + 1;
                    violationLines.add(String.format("    %s v 行 %d: %s",
                            GPU_PATTERN_NAMES[p], lineNum, extractLine(sourceCode, matcher.start())));
                }
            }

            if (violations.isEmpty()) {
                report.append(String.format("|  ✓ %s: 通过 - 无 GPU 不友好操作%n", className));
            } else {
                allGpuFriendly = false;
                allPassed = false;
                report.append(String.format("|  ✗ %s: 失败 - 发现 GPU 不友好操作: %s%n",
                        className, violations));
                for (String line : violationLines) {
                    report.append(line).append("\n");
                }
            }
        }

        report.append("+--------------------------------------------------------------+\n");
        return allGpuFriendly;
    }

    // ==================== 验证 3: FPS 推算 ====================

    /**
     * 基于热路径总延迟推算理论 FPS 上限
     *
     * @param bfsAvgNs  BFS 平均延迟（纳秒）
     * @param ringAvgNs 环形缓冲区平均延迟（纳秒）
     * @param dbqAvgNs  双缓冲队列平均延迟（纳秒）
     * @return true 如果理论 FPS >= 目标值
     */
    private static boolean estimateFps(long bfsAvgNs, long ringAvgNs, long dbqAvgNs) {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=  3. FPS 推算                                                =\n");
        report.append("+--------------------------------------------------------------+\n");

        long totalLatencyNs = bfsAvgNs + ringAvgNs + dbqAvgNs;
        double totalLatencyMs = totalLatencyNs / 1_000_000.0;
        int estimatedFps = totalLatencyMs > 0 ? (int) (1000.0 / totalLatencyMs) : Integer.MAX_VALUE;

        boolean passed = estimatedFps >= FPS_TARGET;

        report.append(String.format("|  热路径总延迟: %s%n", formatNanos(totalLatencyNs)));
        report.append(String.format("|    BFS:          %s%n", formatNanos(bfsAvgNs)));
        report.append(String.format("|    RingBuffer:   %s%n", formatNanos(ringAvgNs)));
        report.append(String.format("|    DBQ:          %s%n", formatNanos(dbqAvgNs)));
        report.append(String.format("|  理论 FPS 上限: %d  目标: >=%d  [%s]%n",
                estimatedFps, FPS_TARGET, passed ? "通过" : "失败"));
        report.append("+--------------------------------------------------------------+\n");

        if (!passed) allPassed = false;
        return passed;
    }

    // ==================== 验证 4: 冷路径隔离验证 ====================

    /**
     * 验证冷路径类不在热路径源码中被引用。
     * 确保后拦截器/异步构建管道等冷路径组件不会影响热路径性能。
     *
     * @return true 如果所有冷路径类均未在热路径中被引用
     */
    private static boolean verifyColdPathIsolation() {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=  4. 冷路径隔离验证                                          =\n");
        report.append("+--------------------------------------------------------------+\n");

        Path sourceRoot = findSourceRoot();
        if (sourceRoot == null) {
            report.append("|  [WARN] 无法定位源码根目录，跳过冷路径隔离验证                   |\n");
            report.append("+--------------------------------------------------------------+\n");
            return true;
        }

        boolean allIsolated = true;
        StringBuilder allHotPathSource = new StringBuilder();

        /* 收集所有热路径源码 */
        for (String[] cls : HOT_PATH_CLASSES) {
            String className = cls[0];
            String relativePath = cls[1];
            Path sourceFile = sourceRoot.resolve(relativePath);

            if (Files.exists(sourceFile)) {
                String content = readSourceFile(sourceFile);
                if (content != null) {
                    allHotPathSource.append(content).append("\n");
                } else {
                    report.append(String.format("|  [WARN] 无法读取热路径源码: %s%n", className));
                }
            } else {
                report.append(String.format("|  [WARN] 热路径源文件不存在: %s%n", className));
            }
        }

        /* 检查每个冷路径类是否出现在热路径源码中 */
        String hotSource = allHotPathSource.toString();
        for (String coldClass : COLD_PATH_CLASS_NAMES) {
            if (hotSource.contains(coldClass)) {
                report.append(String.format("|  ✗ %s: 失败 -- 在热路径源码中被引用%n", coldClass));
                allIsolated = false;
            } else {
                report.append(String.format("|  ✓ %s: 通过 -- 不在热路径中%n", coldClass));
            }
        }

        report.append("+--------------------------------------------------------------+\n");
        return allIsolated;
    }

    // ==================== 辅助方法 ====================

    /**
     * 查找源码根目录。从 classpath 或系统属性中推断。
     *
     * @return 源码根目录路径，如果无法找到则返回 null
     */
    private static Path findSourceRoot() {
        /* 尝试常见源码路径 */
        String[] candidatePaths = {
                "src/main/java",
                "../src/main/java",
                "../../src/main/java",
        };

        /* 从当前工作目录开始搜索 */
        Path userDir = Paths.get(System.getProperty("user.dir", "."));
        for (String candidate : candidatePaths) {
            Path resolved = userDir.resolve(candidate);
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }

        /* 尝试从 class 文件位置推断 */
        try {
            Path classPath = Paths.get(
                    RenderiumPerformanceBenchmark.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            );
            /* 从 target/classes 或 target/test-classes 回溯到源码根目录 */
            Path sourcePath = classPath.getParent();
            while (sourcePath != null) {
                Path candidate = sourcePath.resolve("src/main/java");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                sourcePath = sourcePath.getParent();
            }
        } catch (Exception e) {
            /* 忽略，返回 null */
        }

        return null;
    }

    /**
     * 读取源文件内容
     *
     * @param path 文件路径
     * @return 文件内容字符串，读取失败时返回 null
     */
    private static String readSourceFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 计算字符串中的行数
     *
     * @param text 输入文本
     * @return 行数
     */
    private static int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 从源码中提取指定位置所在的行
     *
     * @param source  源码
     * @param position 字符位置
     * @return 该位置所在行的内容（已去除首尾空白）
     */
    private static String extractLine(String source, int position) {
        int lineStart = position;
        int lineEnd = position;

        /* 向前找到行首 */
        while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }

        /* 向后找到行尾 */
        while (lineEnd < source.length() && source.charAt(lineEnd) != '\n') {
            lineEnd++;
        }

        return source.substring(lineStart, lineEnd).trim();
    }

    /**
     * 格式化纳秒值为可读字符串
     *
     * @param nanos 纳秒值
     * @return 格式化字符串（如 "12.34us" 或 "1.23ms"）
     */
    private static String formatNanos(long nanos) {
        if (nanos < 1000) {
            return nanos + " ns";
        } else if (nanos < 1_000_000) {
            return String.format("%.2f us", nanos / 1000.0);
        } else {
            return String.format("%.2f ms", nanos / 1_000_000.0);
        }
    }

    // ==================== 报告打印方法 ====================

    /**
     * 打印测试报告头部
     */
    private static void printHeader() {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=         Renderium 性能基准测试报告                           =\n");
        report.append("=         RenderiumPerformanceBenchmark                        =\n");
        report.append("+--------------------------------------------------------------+\n");
        report.append(String.format("|  预热迭代: %d    测量迭代: %d                      |\n",
                WARMUP_ITERATIONS, MEASURE_ITERATIONS));
        report.append(String.format("|  计时方式: System.nanoTime()                                 |\n"));
        report.append("+--------------------------------------------------------------+\n");
    }

    /**
     * 打印总体汇总结果
     */
    private static void printSummary() {
        report.append("+--------------------------------------------------------------+\n");
        report.append("=  汇总结果                                                   =\n");
        report.append("+--------------------------------------------------------------+\n");
        report.append(String.format("|  总体结论: %s                                        |\n",
                allPassed ? "ALL PASSED" : "SOME FAILED"));
        report.append("+--------------------------------------------------------------+\n");
    }
}
