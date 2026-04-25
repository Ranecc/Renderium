// Renderium - ModuleRegistry 拓扑排序单元测试

package com.renderium.module;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * ModuleRegistry 拓扑排序测试
 * <p>
 * 验证 Kahn 算法的实现正确性，包括：
 * - 依赖顺序正确性
 * - 循环依赖检测
 * - 边界情况处理
 * - 优先级排序
 *
 * @author Renderium Team
 * @since 2.0.0
 */
@DisplayName("ModuleRegistry 拓扑排序测试")
class ModuleRegistryTopologyTest {

    /**
     * 辅助类：创建测试用模块
     */
    private static class TestModule implements RenderiumModule {
        private final ModuleMetadata metadata;
        private boolean initialized = false;
        private boolean enabled = false;

        /**
         * 创建测试模块
         *
         * @param id 模块 ID
         * @param category 模块分类
         * @param dependencies 依赖列表
         */
        TestModule(String id, ModuleCategory category, List<String> dependencies) {
            this.metadata = new ModuleMetadata(
                    id,
                    "Test Module: " + id,
                    "1.0.0",
                    category,
                    dependencies,
                    List.of(),
                    "Test module for " + id,
                    "Renderium Team",
                    true
            );
        }

        @Override
        public ModuleMetadata getMetadata() {
            return metadata;
        }

        @Override
        public boolean canLoad(ModuleContext context) {
            return true;
        }

        @Override
        public boolean initialize(ModuleContext context) {
            this.initialized = true;
            return true;
        }

        @Override
        public boolean enable() {
            this.enabled = true;
            return true;
        }

        @Override
        public void disable() {
            this.enabled = false;
        }

        @Override
        public void dispose() {
            this.initialized = false;
            this.enabled = false;
        }

        public boolean isInitialized() {
            return initialized;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("无依赖模块按优先级排序")
    void testNoDependenciesSortByPriority() throws Exception {
        // 创建三个无依赖的模块，不同优先级
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("low-priority", ModuleCategory.SHADER, List.of()));
        registry.register(new TestModule("high-priority", ModuleCategory.SYSTEM, List.of()));
        registry.register(new TestModule("mid-priority", ModuleCategory.CORE, List.of()));

        // 通过反射调用私有方法 resolveLoadOrder
        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        // 验证顺序：SYSTEM(100) > CORE(80) > SHADER(30)
        assertEquals(3, order.size());
        assertEquals("high-priority", order.get(0), "SYSTEM 模块应最先加载");
        assertEquals("mid-priority", order.get(1), "CORE 模块应第二加载");
        assertEquals("low-priority", order.get(2), "SHADER 模块应最后加载");
    }

    @Test
    @DisplayName("有依赖模块按依赖关系排序")
    void testDependenciesOrder() throws Exception {
        // 创建模块链：A -> B -> C（C 依赖 B，B 依赖 A）
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of()));
        registry.register(new TestModule("module-b", ModuleCategory.CORE, List.of("module-a")));
        registry.register(new TestModule("module-c", ModuleCategory.CORE, List.of("module-b")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        // 验证依赖顺序：A -> B -> C
        assertEquals(3, order.size());
        assertTrue(order.indexOf("module-a") < order.indexOf("module-b"),
                "module-a 应在 module-b 之前加载");
        assertTrue(order.indexOf("module-b") < order.indexOf("module-c"),
                "module-b 应在 module-c 之前加载");
    }

    @Test
    @DisplayName("多依赖模块排序正确")
    void testMultipleDependencies() throws Exception {
        // 模块 D 依赖 A, B, C
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of()));
        registry.register(new TestModule("module-b", ModuleCategory.CORE, List.of()));
        registry.register(new TestModule("module-c", ModuleCategory.CORE, List.of()));
        registry.register(new TestModule("module-d", ModuleCategory.CORE, List.of("module-a", "module-b", "module-c")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        // 验证：A, B, C 都应在 D 之前
        assertEquals(4, order.size());
        int dIndex = order.indexOf("module-d");
        assertTrue(order.indexOf("module-a") < dIndex, "module-a 应在 module-d 之前");
        assertTrue(order.indexOf("module-b") < dIndex, "module-b 应在 module-d 之前");
        assertTrue(order.indexOf("module-c") < dIndex, "module-c 应在 module-d 之前");
    }

    @Test
    @DisplayName("循环依赖检测 - 两个模块")
    void testCircularDependencyTwoModules() throws Exception {
        // A 依赖 B，B 依赖 A
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of("module-b")));
        registry.register(new TestModule("module-b", ModuleCategory.CORE, List.of("module-a")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        // 应抛出异常（通过 InvocationTargetException 包装）
        Exception exception = assertThrows(Exception.class, () -> {
            resolveLoadOrderMethod.invoke(registry);
        });

        // 验证异常消息包含循环依赖信息
        String message = exception.getCause().getMessage();
        assertTrue(message.contains("Circular dependency detected"),
                "异常消息应包含循环依赖提示");
    }

    @Test
    @DisplayName("循环依赖检测 - 三个模块")
    void testCircularDependencyThreeModules() throws Exception {
        // A -> B -> C -> A（循环）
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of("module-c")));
        registry.register(new TestModule("module-b", ModuleCategory.CORE, List.of("module-a")));
        registry.register(new TestModule("module-c", ModuleCategory.CORE, List.of("module-b")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        Exception exception = assertThrows(Exception.class, () -> {
            resolveLoadOrderMethod.invoke(registry);
        });

        String message = exception.getCause().getMessage();
        assertTrue(message.contains("Circular dependency detected"),
                "异常消息应包含循环依赖提示");
    }

    @Test
    @DisplayName("依赖模块未注册抛出异常")
    void testUnregisteredDependency() throws Exception {
        // 模块 A 依赖不存在的模块 X
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of("module-x")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        Exception exception = assertThrows(Exception.class, () -> {
            resolveLoadOrderMethod.invoke(registry);
        });

        Throwable cause = exception.getCause();
        assertTrue(cause instanceof IllegalArgumentException,
                "应抛出 IllegalArgumentException");
        assertTrue(cause.getMessage().contains("not registered"),
                "异常消息应包含未注册提示");
    }

    @Test
    @DisplayName("空注册表返回空列表")
    void testEmptyRegistry() throws Exception {
        ModuleRegistry registry = new ModuleRegistry();

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        assertNotNull(order);
        assertTrue(order.isEmpty(), "空注册表应返回空列表");
    }

    @Test
    @DisplayName("单个模块直接返回")
    void testSingleModule() throws Exception {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("only-module", ModuleCategory.CORE, List.of()));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        assertEquals(1, order.size());
        assertEquals("only-module", order.get(0));
    }

    @Test
    @DisplayName("复杂依赖图排序正确")
    void testComplexDependencyGraph() throws Exception {
        // 构建复杂依赖图：
        //     SYSTEM
        //    /      \
        //   CORE-A  CORE-B
        //     \     /
        //    OPTIMIZATION
        //         |
        //       SHADER
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("system", ModuleCategory.SYSTEM, List.of()));
        registry.register(new TestModule("core-a", ModuleCategory.CORE, List.of("system")));
        registry.register(new TestModule("core-b", ModuleCategory.CORE, List.of("system")));
        registry.register(new TestModule("optimization", ModuleCategory.OPTIMIZATION, List.of("core-a", "core-b")));
        registry.register(new TestModule("shader", ModuleCategory.SHADER, List.of("optimization")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        assertEquals(5, order.size());

        // 验证依赖关系
        int systemIdx = order.indexOf("system");
        int coreAIdx = order.indexOf("core-a");
        int coreBIdx = order.indexOf("core-b");
        int optIdx = order.indexOf("optimization");
        int shaderIdx = order.indexOf("shader");

        assertTrue(systemIdx < coreAIdx, "system 应在 core-a 之前");
        assertTrue(systemIdx < coreBIdx, "system 应在 core-b 之前");
        assertTrue(coreAIdx < optIdx, "core-a 应在 optimization 之前");
        assertTrue(coreBIdx < optIdx, "core-b 应在 optimization 之前");
        assertTrue(optIdx < shaderIdx, "optimization 应在 shader 之前");
    }

    @Test
    @DisplayName("优先级在依赖关系相同的情况下起作用")
    void testPriorityWithSameDependencies() throws Exception {
        // A 和 B 都无依赖，但优先级不同
        // C 依赖 A 和 B
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("low-pri", ModuleCategory.SHADER, List.of()));
        registry.register(new TestModule("high-pri", ModuleCategory.SYSTEM, List.of()));
        registry.register(new TestModule("depends-on-both", ModuleCategory.CORE, List.of("low-pri", "high-pri")));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> order = (List<String>) resolveLoadOrderMethod.invoke(registry);

        assertEquals(3, order.size());
        assertEquals("high-pri", order.get(0), "SYSTEM 优先级最高应最先");
        assertEquals("low-pri", order.get(1), "SHADER 优先级低应第二");
        assertEquals("depends-on-both", order.get(2), "有依赖的模块应最后");
    }

    @Test
    @DisplayName("部分循环依赖不影响无环模块")
    void testPartialCircularDependency() throws Exception {
        // A -> B -> C -> A（循环）
        // D 无依赖（独立模块）
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("module-a", ModuleCategory.CORE, List.of("module-c")));
        registry.register(new TestModule("module-b", ModuleCategory.CORE, List.of("module-a")));
        registry.register(new TestModule("module-c", ModuleCategory.CORE, List.of("module-b")));
        registry.register(new TestModule("module-d", ModuleCategory.SYSTEM, List.of()));

        Method resolveLoadOrderMethod = ModuleRegistry.class.getDeclaredMethod("resolveLoadOrder");
        resolveLoadOrderMethod.setAccessible(true);

        // 即使有部分循环，整体也应抛出异常
        Exception exception = assertThrows(Exception.class, () -> {
            resolveLoadOrderMethod.invoke(registry);
        });

        String message = exception.getCause().getMessage();
        assertTrue(message.contains("Circular dependency detected"),
                "应检测到循环依赖");
    }
}
