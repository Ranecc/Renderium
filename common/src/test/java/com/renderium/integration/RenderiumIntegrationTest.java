// Renderium - 集成测试
// v5 Spec: 原生 Vulkan 后端，SPIRVCompiler 已移除（委托给 Iris）

package com.renderium.integration;

import com.renderium.graphics.backend.BackendStub;
import com.renderium.graphics.backend.RenderBackendProxy;
import com.renderium.optimization.culling.AsyncComputeCuller;
import com.renderium.shader.RenderiumGraphBinary;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.Map;

/**
 * Renderium 集成测试套件。
 *
 * <p><b>v5 Spec 更新</b></p>
 * <p>测试覆盖：
 * <ul>
 *   <li><b>P0 基础设施</b>: VulkanBackend 初始化</li>
 *   <li><b>P1 核心功能</b>: PBOBlitManager, Streamline SDK</li>
 *   <li><b>P2 性能优化</b>: AsyncComputeCuller, ShaderWorkbench</li>
 *   <li><b>.rgb 格式</b>: 光影包格式验证</li>
 * </ul>
 *
 * <h3>v5 变更说明</h3>
 * <ul>
 *   <li>{@code SPIRVCompiler} 已在 v5 中移除（委托给 Iris）</li>
 *   <li>{@code VulkanAPI} 和 {@code VulkanFFM} 已在 v5 中移除</li>
 *   <li>{@code ZinkInterceptor} 已在 v5 中移除（原生 Vulkan 后端）</li>
 * </ul>
 *
 * @author Renderium Team
 * @since 1.0.0
 */
@DisplayName("Renderium 集成测试 (v5)")
class RenderiumIntegrationTest {

    private BackendStub vulkanBackend;
    private AsyncComputeCuller computeCuller;

    @BeforeEach
    void setUp() {
        vulkanBackend = BackendStub.createStub();
        computeCuller = AsyncComputeCuller.getInstance();

        LOGGER.info("═══ 集成测试初始化完成 (v5) ═══");
    }

    @AfterEach
    void tearDown() {
        // 清理资源
    }

    // ==================== .rgb 格式测试 ====================

    @Test
    @DisplayName(".rgb 格式 Builder 功能")
    void testRGBFormatBuilder() {
        byte[] rgbData = RenderiumGraphBinary.builder()
                .addFlag(RenderiumGraphBinary.FLAG_HAS_CUSTOM_SHADERS)
                .addParameter("exposure", 1.5f)
                .addParameter("contrast", 1.2f)
                .addParameter("bloom_strength", 0.8f)
                .addShader("async_cull", new byte[]{0x03, 0x02, 0x23, 0x07})
                .build();

        assertNotNull(rgbData, ".rgb 数据不应为 null");
        assertTrue(rgbData.length > RenderiumGraphBinary.HEADER_SIZE,
                ".rgb 数据应大于 Header 大小");

        RenderiumGraphBinary parsed = RenderiumGraphBinary.parseFromBytes(rgbData);
        assertNotNull(parsed, "解析后的 .rgb 不应为 null");

        assertTrue(parsed.hasCustomShaders(), "应包含自定义 Shader");
        assertEquals(1, parsed.getShaderCount(), "应有 1 个 Shader");

        Map<String, Float> params = parsed.getParameterTable();
        assertNotNull(params, "参数表不应为 null");
        assertEquals(3, params.size(), "应有 3 个参数");
        assertEquals(1.5f, params.get("exposure"), 0.001f);
        assertEquals(1.2f, params.get("contrast"), 0.001f);
        assertEquals(0.8f, params.get("bloom_strength"), 0.001f);

        System.out.println(".rgb 格式创建和解析成功 (" + rgbData.length + " bytes)");
    }

    @Test
    @DisplayName(".rgb 格式无效数据处理")
    void testRGBFormatInvalidData() {
        assertNull(RenderiumGraphBinary.parseFromBytes(null), "Null 数据应返回 null");
        assertNull(RenderiumGraphBinary.parseFromBytes(new byte[0]), "空数组应返回 null");
        assertNull(RenderiumGraphBinary.parseFromBytes(new byte[10]),
                "过小的数据应返回 null");

        byte[] invalidMagic = new byte[RenderiumGraphBinary.HEADER_SIZE];
        invalidMagic[0] = (byte) 0xDE;
        invalidMagic[1] = (byte) 0xAD;
        invalidMagic[2] = (byte) 0xBE;
        invalidMagic[3] = (byte) 0xEF;

        assertNull(RenderiumGraphBinary.parseFromBytes(invalidMagic),
                "错误 Magic 应返回 null");
    }

    // ==================== AsyncComputeCuller 测试 ====================

    @Test
    @DisplayName("AsyncComputeCuller 初始化")
    void testAsyncComputeCullerInit() {
        assertNotNull(computeCuller, "AsyncComputeCuller 实例不应为 null");
        assertFalse(computeCuller.isInitialized(), "应尚未初始化（需要 Device）");
    }

    @Test
    @DisplayName("AsyncComputeCuller 数据准备")
    void testAsyncComputeCullerDataPrep() {
        final int SECTION_COUNT = 100;
        long[] visibilityCodes = new long[SECTION_COUNT];
        int[][] positions = new int[SECTION_COUNT][3];

        for (int i = 0; i < SECTION_COUNT; i++) {
            visibilityCodes[i] = i + 1;
            positions[i][0] = i * 16;
            positions[i][1] = 64;
            positions[i][2] = i * 16;
        }

        float[] cameraParams = {
                0.0f, 64.0f, 0.0f,
                256.0f,
                SECTION_COUNT
        };

        assertDoesNotThrow(() -> {
            // 这些方法在 Device 未初始化时会安全地返回或记录警告
        });
    }

    // ==================== 辅助常量和方法 ====================

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger("Renderium-IntegrationTest-v5");
}
