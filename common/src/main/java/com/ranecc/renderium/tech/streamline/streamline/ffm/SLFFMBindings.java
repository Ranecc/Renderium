// Renderium - Streamline SDK FFM Bindings
// Java 22+ Panama FFM API 绑定 NVIDIA Streamline SDK 2.10.3
// 基于 sl_core_api.h, sl_dlss.h, sl_dlss_g.h 等头文件

package com.ranecc.renderium.tech.streamline.streamline.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.logging.Logger;

/**
 * Streamline SDK FFM 绑定
 * <p>
 * 使用 Java 22+ Panama FFM API 直接调用 NVIDIA Streamline SDK。
 * 基于 Streamline SDK 2.10.3 的 C API（sl_core_api.h）。
 * <p>
 * 核心 API 映射：
 * <ul>
 *   <li>slInit → 初始化 Streamline</li>
 *   <li>slShutdown → 关闭 Streamline</li>
 *   <li>slIsFeatureSupported → 检查特性支持</li>
 *   <li>slGetNewFrameToken → 获取帧标记</li>
 *   <li>slSetTagForFrame → 标记帧资源</li>
 *   <li>slSetConstants → 设置常量</li>
 *   <li>slEvaluateFeature → 评估特性</li>
 *   <li>slAllocateResources / slFreeResources → 资源管理</li>
 * </ul>
 *
 * @see <a href="https://github.com/NVIDIA-RTX/Streamline">Streamline SDK</a>
 */
public final class SLFFMBindings {

    private static final Logger LOGGER = Logger.getLogger(SLFFMBindings.class.getName());

    private static SymbolLookup streamlineLookup;
    private static boolean loaded = false;

    private SLFFMBindings() {}

    // ==================== Streamline 核心 API ====================

    // slInit(const sl::Preferences& pref, uint64_t sdkVersion) -> sl::Result
    private static MethodHandle mhSlInit;

    // slShutdown() -> sl::Result
    private static MethodHandle mhSlShutdown;

    // slIsFeatureSupported(sl::Feature feature, const sl::AdapterInfo& adapterInfo) -> sl::Result
    private static MethodHandle mhSlIsFeatureSupported;

    // slIsFeatureLoaded(sl::Feature feature, bool& loaded) -> sl::Result
    private static MethodHandle mhSlIsFeatureLoaded;

    // slSetFeatureLoaded(sl::Feature feature, bool loaded) -> sl::Result
    private static MethodHandle mhSlSetFeatureLoaded;

    // slGetNewFrameToken(sl::FrameToken*& token, const uint32_t* frameIndex) -> sl::Result
    private static MethodHandle mhSlGetNewFrameToken;

    // slSetTagForFrame(const sl::FrameToken& frame, const sl::ViewportHandle& viewport,
    //                  const sl::ResourceTag* resources, uint32_t numResources,
    //                  sl::CommandBuffer* cmdBuffer) -> sl::Result
    private static MethodHandle mhSlSetTagForFrame;

    // slSetConstants(const sl::Constants& values, const sl::FrameToken& frame,
    //                const sl::ViewportHandle& viewport) -> sl::Result
    private static MethodHandle mhSlSetConstants;

    // slEvaluateFeature(sl::Feature feature, const sl::FrameToken& frame,
    //                    const sl::BaseStructure** inputs, uint32_t numInputs,
    //                    sl::CommandBuffer* cmdBuffer) -> sl::Result
    private static MethodHandle mhSlEvaluateFeature;

    // slAllocateResources(sl::CommandBuffer* cmdBuffer, sl::Feature feature,
    //                      const sl::ViewportHandle& viewport) -> sl::Result
    private static MethodHandle mhSlAllocateResources;

    // slFreeResources(sl::Feature feature, const sl::ViewportHandle& viewport) -> sl::Result
    private static MethodHandle mhSlFreeResources;

    // slGetFeatureRequirements(sl::Feature feature, sl::FeatureRequirements& requirements) -> sl::Result
    private static MethodHandle mhSlGetFeatureRequirements;

    // slGetFeatureVersion(sl::Feature feature, sl::FeatureVersion& version) -> sl::Result
    private static MethodHandle mhSlGetFeatureVersion;

    // slGetFeatureFunction(sl::Feature feature, const char* functionName, void*& function) -> sl::Result
    private static MethodHandle mhSlGetFeatureFunction;

    // ==================== DLSS 特定 API ====================

    // slDLSSGetOptimalSettings(const sl::DLSSOptions& options, sl::DLSSOptimalSettings& settings) -> sl::Result
    private static MethodHandle mhSlDLSSGetOptimalSettings;

    // slDLSSSetOptions(const sl::ViewportHandle& viewport, const sl::DLSSOptions& options) -> sl::Result
    private static MethodHandle mhSlDLSSSetOptions;

    // slDLSSGetState(const sl::ViewportHandle& viewport, sl::DLSSState& state) -> sl::Result
    private static MethodHandle mhSlDLSSGetState;

    // ==================== DLSS-G 特定 API ====================

    // slDLSSGSetOptions(const sl::ViewportHandle& viewport, const sl::DLSSGOptions& options) -> sl::Result
    private static MethodHandle mhSlDLSSGSetOptions;

    // slDLSSGGetState(const sl::ViewportHandle& viewport, sl::DLSSGState& state,
    //                  const sl::DLSSGOptions* options) -> sl::Result
    private static MethodHandle mhSlDLSSGGetState;

    // ==================== Vulkan 特定 API ====================

    // slSetVulkanInfo(const sl::VulkanInfo& info) -> sl::Result
    private static MethodHandle mhSlSetVulkanInfo;

    // ==================== Streamline 常量 ====================

    /**
     * Streamline SDK 版本号
     * 对应 sl_version.h: kSDKVersion = (2 << 48) | (10 << 32) | (3 << 16) | 0xfedc
     */
    public static final long SL_SDK_VERSION = (2L << 48) | (10L << 32) | (3L << 16) | 0xfedcL;

    /**
     * Streamline Feature 常量
     * 对应 sl_core_types.h 中的 kFeature* 定义
     */
    public static final int FEATURE_DLSS = 0;
    public static final int FEATURE_NIS = 2;
    public static final int FEATURE_REFLEX = 3;
    public static final int FEATURE_PCL = 4;
    public static final int FEATURE_DEEP_DVC = 5;
    public static final int FEATURE_LATEWARP = 6;
    public static final int FEATURE_DLSS_G = 1000;
    public static final int FEATURE_DLSS_RR = 1001;
    public static final int FEATURE_NVPERF = 1002;
    public static final int FEATURE_DIRECT_SR = 1003;
    public static final int FEATURE_IMGUI = 9999;
    public static final int FEATURE_COMMON = 0xFFFFFFFF;

    /**
     * Streamline BufferType 常量
     * 对应 sl_core_types.h 中的 kBufferType* 定义
     */
    public static final int BUFFER_TYPE_DEPTH = 0;
    public static final int BUFFER_TYPE_MOTION_VECTORS = 1;
    public static final int BUFFER_TYPE_HUDLESS_COLOR = 2;
    public static final int BUFFER_TYPE_SCALING_INPUT_COLOR = 3;
    public static final int BUFFER_TYPE_SCALING_OUTPUT_COLOR = 4;
    public static final int BUFFER_TYPE_NORMALS = 5;
    public static final int BUFFER_TYPE_EXPOSURE = 13;
    public static final int BUFFER_TYPE_UI_COLOR_AND_ALPHA = 23;
    public static final int BUFFER_TYPE_BACKBUFFER = 53;

    /**
     * Streamline Result 枚举
     * 对应 sl_result.h 中的 sl::Result
     */
    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR_IO = 1;
    public static final int RESULT_ERROR_DRIVER_OUT_OF_DATE = 2;
    public static final int RESULT_ERROR_NO_SUPPORTED_ADAPTER_FOUND = 5;
    public static final int RESULT_ERROR_VULKAN_API = 8;
    public static final int RESULT_ERROR_NGX_FAILED = 12;
    public static final int RESULT_ERROR_NOT_INITIALIZED = 17;
    public static final int RESULT_ERROR_FEATURE_MISSING = 24;
    public static final int RESULT_ERROR_FEATURE_NOT_SUPPORTED = 25;

    /**
     * DLSS 模式枚举
     * 对应 sl_dlss.h 中的 sl::DLSSMode
     */
    public static final int DLSS_MODE_OFF = 0;
    public static final int DLSS_MODE_MAX_PERFORMANCE = 1;
    public static final int DLSS_MODE_BALANCED = 2;
    public static final int DLSS_MODE_MAX_QUALITY = 3;
    public static final int DLSS_MODE_ULTRA_PERFORMANCE = 4;
    public static final int DLSS_MODE_ULTRA_QUALITY = 5;
    public static final int DLSS_MODE_DLAA = 6;

    /**
     * DLSS-G 模式枚举
     * 对应 sl_dlss_g.h 中的 sl::DLSSGMode
     */
    public static final int DLSSG_MODE_OFF = 0;
    public static final int DLSSG_MODE_ON = 1;
    public static final int DLSSG_MODE_AUTO = 2;

    /**
     * ResourceLifecycle 枚举
     * 对应 sl_core_types.h
     */
    public static final int RESOURCE_LIFECYCLE_ONLY_VALID_NOW = 0;
    public static final int RESOURCE_LIFECYCLE_VALID_UNTIL_PRESENT = 1;
    public static final int RESOURCE_LIFECYCLE_VALID_UNTIL_EVALUATE = 2;

    /**
     * PreferenceFlags 位标志
     * 对应 sl_core_types.h 中的 sl::PreferenceFlags
     */
    public static final long PREFERENCE_FLAG_DISABLE_CL_STATE_TRACKING = 1L << 0;
    public static final long PREFERENCE_FLAG_DISABLE_DEBUG_TEXT = 1L << 1;
    public static final long PREFERENCE_FLAG_ALLOW_OTA = 1L << 3;
    public static final long PREFERENCE_FLAG_LOAD_DOWNLOADED_PLUGINS = 1L << 6;
    public static final long PREFERENCE_FLAG_USE_FRAME_BASED_RESOURCE_TAGGING = 1L << 7;

    /**
     * 加载 Streamline SDK
     * <p>
     * 加载 sl.interposer.dll 并解析所有核心 API 函数。
     * 必须在任何其他 API 调用之前调用。
     *
     * @param libraryPath sl.interposer.dll 的完整路径
     * @return 是否加载成功
     */
    public static synchronized boolean load(String libraryPath) {
        if (loaded) return true;

        try {
            streamlineLookup = SymbolLookup.libraryLookup(libraryPath, Arena.ofAuto());

            // 解析核心 API
            mhSlInit = resolve("slInit");
            mhSlShutdown = resolve("slShutdown");
            mhSlIsFeatureSupported = resolve("slIsFeatureSupported");
            mhSlIsFeatureLoaded = resolve("slIsFeatureLoaded");
            mhSlSetFeatureLoaded = resolve("slSetFeatureLoaded");
            mhSlGetNewFrameToken = resolve("slGetNewFrameToken");
            mhSlSetTagForFrame = resolve("slSetTagForFrame");
            mhSlSetConstants = resolve("slSetConstants");
            mhSlEvaluateFeature = resolve("slEvaluateFeature");
            mhSlAllocateResources = resolve("slAllocateResources");
            mhSlFreeResources = resolve("slFreeResources");
            mhSlGetFeatureRequirements = resolve("slGetFeatureRequirements");
            mhSlGetFeatureVersion = resolve("slGetFeatureVersion");
            mhSlGetFeatureFunction = resolve("slGetFeatureFunction");

            // 解析 Vulkan 特定 API
            mhSlSetVulkanInfo = resolve("slSetVulkanInfo");

            loaded = true;
            LOGGER.info("Streamline SDK loaded successfully from: " + libraryPath);
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to load Streamline SDK: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查 SDK 是否已加载
     */
    public static boolean isLoaded() {
        return loaded;
    }

    // ==================== 核心 API 调用 ====================

    /**
     * 初始化 Streamline SDK
     * <p>
     * 对应 C API: sl::Result slInit(const sl::Preferences& pref, uint64_t sdkVersion)
     *
     * @param preferencesPtr 指向 sl::Preferences 结构的 MemorySegment
     * @return Result 枚举值
     */
    public static int slInit(MemorySegment preferencesPtr) {
        checkLoaded();
        try {
            return (int) mhSlInit.invokeExact(preferencesPtr, SL_SDK_VERSION);
        } catch (Throwable t) {
            throw new SLException("slInit failed", t);
        }
    }

    /**
     * 关闭 Streamline SDK
     * <p>
     * 对应 C API: sl::Result slShutdown()
     *
     * @return Result 枚举值
     */
    public static int slShutdown() {
        checkLoaded();
        try {
            return (int) mhSlShutdown.invokeExact();
        } catch (Throwable t) {
            throw new SLException("slShutdown failed", t);
        }
    }

    /**
     * 检查特性是否支持
     * <p>
     * 对应 C API: sl::Result slIsFeatureSupported(sl::Feature feature, const sl::AdapterInfo& adapterInfo)
     *
     * @param feature 特性 ID（如 FEATURE_DLSS）
     * @param adapterInfoPtr 适配器信息指针（可以为 null）
     * @return Result 枚举值（RESULT_OK 表示支持）
     */
    public static int slIsFeatureSupported(int feature, MemorySegment adapterInfoPtr) {
        checkLoaded();
        try {
            return (int) mhSlIsFeatureSupported.invokeExact(feature, adapterInfoPtr);
        } catch (Throwable t) {
            throw new SLException("slIsFeatureSupported failed", t);
        }
    }

    /**
     * 检查特性是否已加载
     * <p>
     * 对应 C API: sl::Result slIsFeatureLoaded(sl::Feature feature, bool& loaded)
     *
     * @param feature 特性 ID
     * @param loadedPtr 输出布尔值指针
     * @return Result 枚举值
     */
    public static int slIsFeatureLoaded(int feature, MemorySegment loadedPtr) {
        checkLoaded();
        try {
            return (int) mhSlIsFeatureLoaded.invokeExact(feature, loadedPtr);
        } catch (Throwable t) {
            throw new SLException("slIsFeatureLoaded failed", t);
        }
    }

    /**
     * 获取新帧标记
     * <p>
     * 对应 C API: sl::Result slGetNewFrameToken(sl::FrameToken*& token, const uint32_t* frameIndex)
     *
     * @param tokenPtr 输出帧标记指针
     * @param frameIndexPtr 帧索引指针（可以为 null）
     * @return Result 枚举值
     */
    public static int slGetNewFrameToken(MemorySegment tokenPtr, MemorySegment frameIndexPtr) {
        checkLoaded();
        try {
            return (int) mhSlGetNewFrameToken.invokeExact(tokenPtr, frameIndexPtr);
        } catch (Throwable t) {
            throw new SLException("slGetNewFrameToken failed", t);
        }
    }

    /**
     * 为帧标记资源
     * <p>
     * 对应 C API: sl::Result slSetTagForFrame(const sl::FrameToken& frame,
     *              const sl::ViewportHandle& viewport, const sl::ResourceTag* resources,
     *              uint32_t numResources, sl::CommandBuffer* cmdBuffer)
     *
     * @param framePtr 帧标记指针
     * @param viewportPtr 视口句柄指针
     * @param resourceTagsPtr 资源标签数组指针
     * @param numResources 资源数量
     * @param cmdBufferPtr 命令缓冲区指针（可以为 null）
     * @return Result 枚举值
     */
    public static int slSetTagForFrame(MemorySegment framePtr, MemorySegment viewportPtr,
                                        MemorySegment resourceTagsPtr, int numResources,
                                        MemorySegment cmdBufferPtr) {
        checkLoaded();
        try {
            return (int) mhSlSetTagForFrame.invokeExact(
                framePtr, viewportPtr, resourceTagsPtr, numResources, cmdBufferPtr);
        } catch (Throwable t) {
            throw new SLException("slSetTagForFrame failed", t);
        }
    }

    /**
     * 设置常量
     * <p>
     * 对应 C API: sl::Result slSetConstants(const sl::Constants& values,
     *              const sl::FrameToken& frame, const sl::ViewportHandle& viewport)
     *
     * @param constantsPtr 常量结构指针
     * @param framePtr 帧标记指针
     * @param viewportPtr 视口句柄指针
     * @return Result 枚举值
     */
    public static int slSetConstants(MemorySegment constantsPtr, MemorySegment framePtr,
                                      MemorySegment viewportPtr) {
        checkLoaded();
        try {
            return (int) mhSlSetConstants.invokeExact(constantsPtr, framePtr, viewportPtr);
        } catch (Throwable t) {
            throw new SLException("slSetConstants failed", t);
        }
    }

    /**
     * 评估特性
     * <p>
     * 对应 C API: sl::Result slEvaluateFeature(sl::Feature feature,
     *              const sl::FrameToken& frame, const sl::BaseStructure** inputs,
     *              uint32_t numInputs, sl::CommandBuffer* cmdBuffer)
     *
     * @param feature 特性 ID
     * @param framePtr 帧标记指针
     * @param inputsPtr 输入结构指针数组
     * @param numInputs 输入数量
     * @param cmdBufferPtr 命令缓冲区指针
     * @return Result 枚举值
     */
    public static int slEvaluateFeature(int feature, MemorySegment framePtr,
                                         MemorySegment inputsPtr, int numInputs,
                                         MemorySegment cmdBufferPtr) {
        checkLoaded();
        try {
            return (int) mhSlEvaluateFeature.invokeExact(
                feature, framePtr, inputsPtr, numInputs, cmdBufferPtr);
        } catch (Throwable t) {
            throw new SLException("slEvaluateFeature failed", t);
        }
    }

    /**
     * 分配资源
     * <p>
     * 对应 C API: sl::Result slAllocateResources(sl::CommandBuffer* cmdBuffer,
     *              sl::Feature feature, const sl::ViewportHandle& viewport)
     *
     * @param cmdBufferPtr 命令缓冲区指针
     * @param feature 特性 ID
     * @param viewportPtr 视口句柄指针
     * @return Result 枚举值
     */
    public static int slAllocateResources(MemorySegment cmdBufferPtr, int feature,
                                           MemorySegment viewportPtr) {
        checkLoaded();
        try {
            return (int) mhSlAllocateResources.invokeExact(cmdBufferPtr, feature, viewportPtr);
        } catch (Throwable t) {
            throw new SLException("slAllocateResources failed", t);
        }
    }

    /**
     * 释放资源
     * <p>
     * 对应 C API: sl::Result slFreeResources(sl::Feature feature,
     *              const sl::ViewportHandle& viewport)
     *
     * @param feature 特性 ID
     * @param viewportPtr 视口句柄指针
     * @return Result 枚举值
     */
    public static int slFreeResources(int feature, MemorySegment viewportPtr) {
        checkLoaded();
        try {
            return (int) mhSlFreeResources.invokeExact(feature, viewportPtr);
        } catch (Throwable t) {
            throw new SLException("slFreeResources failed", t);
        }
    }

    /**
     * 获取特性函数指针
     * <p>
     * 对应 C API: sl::Result slGetFeatureFunction(sl::Feature feature,
     *              const char* functionName, void*& function)
     * <p>
     * 用于获取 DLSS/DLSS-G 等特性的特定 API 函数。
     *
     * @param feature 特性 ID
     * @param functionName 函数名称
     * @param functionPtr 输出函数指针
     * @return Result 枚举值
     */
    public static int slGetFeatureFunction(int feature, MemorySegment functionName,
                                            MemorySegment functionPtr) {
        checkLoaded();
        try {
            return (int) mhSlGetFeatureFunction.invokeExact(feature, functionName, functionPtr);
        } catch (Throwable t) {
            throw new SLException("slGetFeatureFunction failed", t);
        }
    }

    /**
     * 设置 Vulkan 信息
     * <p>
     * 对应 C API: sl::Result slSetVulkanInfo(const sl::VulkanInfo& info)
     * <p>
     * 必须在 Vulkan 设备创建后、任何特性评估前调用。
     *
     * @param vulkanInfoPtr Vulkan 信息结构指针
     * @return Result 枚举值
     */
    public static int slSetVulkanInfo(MemorySegment vulkanInfoPtr) {
        checkLoaded();
        try {
            return (int) mhSlSetVulkanInfo.invokeExact(vulkanInfoPtr);
        } catch (Throwable t) {
            throw new SLException("slSetVulkanInfo failed", t);
        }
    }

    // ==================== DLSS 特定 API ====================

    /**
     * 获取 DLSS 最佳设置
     * <p>
     * 对应 C API: sl::Result slDLSSGetOptimalSettings(const sl::DLSSOptions& options,
     *              sl::DLSSOptimalSettings& settings)
     * <p>
     * 需要先通过 slGetFeatureFunction 获取函数指针。
     *
     * @param optionsPtr DLSS 选项结构指针
     * @param settingsPtr 输出最佳设置指针
     * @return Result 枚举值
     */
    public static int slDLSSGetOptimalSettings(MemorySegment optionsPtr, MemorySegment settingsPtr) {
        if (mhSlDLSSGetOptimalSettings == null) {
            throw new SLException("slDLSSGetOptimalSettings not resolved - call resolveDLSSFunctions first");
        }
        try {
            return (int) mhSlDLSSGetOptimalSettings.invokeExact(optionsPtr, settingsPtr);
        } catch (Throwable t) {
            throw new SLException("slDLSSGetOptimalSettings failed", t);
        }
    }

    /**
     * 设置 DLSS 选项
     * <p>
     * 对应 C API: sl::Result slDLSSSetOptions(const sl::ViewportHandle& viewport,
     *              const sl::DLSSOptions& options)
     *
     * @param viewportPtr 视口句柄指针
     * @param optionsPtr DLSS 选项结构指针
     * @return Result 枚举值
     */
    public static int slDLSSSetOptions(MemorySegment viewportPtr, MemorySegment optionsPtr) {
        if (mhSlDLSSSetOptions == null) {
            throw new SLException("slDLSSSetOptions not resolved - call resolveDLSSFunctions first");
        }
        try {
            return (int) mhSlDLSSSetOptions.invokeExact(viewportPtr, optionsPtr);
        } catch (Throwable t) {
            throw new SLException("slDLSSSetOptions failed", t);
        }
    }

    /**
     * 设置 DLSS-G 选项
     * <p>
     * 对应 C API: sl::Result slDLSSGSetOptions(const sl::ViewportHandle& viewport,
     *              const sl::DLSSGOptions& options)
     *
     * @param viewportPtr 视口句柄指针
     * @param optionsPtr DLSS-G 选项结构指针
     * @return Result 枚举值
     */
    public static int slDLSSGSetOptions(MemorySegment viewportPtr, MemorySegment optionsPtr) {
        if (mhSlDLSSGSetOptions == null) {
            throw new SLException("slDLSSGSetOptions not resolved - call resolveDLSSGFunctions first");
        }
        try {
            return (int) mhSlDLSSGSetOptions.invokeExact(viewportPtr, optionsPtr);
        } catch (Throwable t) {
            throw new SLException("slDLSSGSetOptions failed", t);
        }
    }

    // ==================== 函数解析 ====================

    /**
     * 解析 DLSS 特性函数
     * <p>
     * 必须在 slInit 之后、slSetVulkanInfo 之后调用。
     * 通过 slGetFeatureFunction 获取 DLSS 特定的 API 函数指针。
     *
     * @return 是否成功解析
     */
    public static boolean resolveDLSSFunctions() {
        try (Arena arena = Arena.ofConfined()) {
            // 解析 slDLSSGetOptimalSettings
            MemorySegment funcName = arena.allocateFrom("slDLSSGetOptimalSettings");
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            int result = slGetFeatureFunction(FEATURE_DLSS, funcName, funcPtr);
            if (result != RESULT_OK) {
                LOGGER.warning("Failed to resolve slDLSSGetOptimalSettings: " + result);
                return false;
            }
            mhSlDLSSGetOptimalSettings = Linker.nativeLinker().downcallHandle(
                funcPtr.get(ValueLayout.ADDRESS, 0),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            // 解析 slDLSSSetOptions
            funcName = arena.allocateFrom("slDLSSSetOptions");
            result = slGetFeatureFunction(FEATURE_DLSS, funcName, funcPtr);
            if (result != RESULT_OK) {
                LOGGER.warning("Failed to resolve slDLSSSetOptions: " + result);
                return false;
            }
            mhSlDLSSSetOptions = Linker.nativeLinker().downcallHandle(
                funcPtr.get(ValueLayout.ADDRESS, 0),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            // 解析 slDLSSGetState
            funcName = arena.allocateFrom("slDLSSGetState");
            result = slGetFeatureFunction(FEATURE_DLSS, funcName, funcPtr);
            if (result == RESULT_OK) {
                mhSlDLSSGetState = Linker.nativeLinker().downcallHandle(
                    funcPtr.get(ValueLayout.ADDRESS, 0),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }

            LOGGER.info("DLSS feature functions resolved successfully");
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to resolve DLSS functions: " + e.getMessage());
            return false;
        }
    }

    /**
     * 解析 DLSS-G 特性函数
     *
     * @return 是否成功解析
     */
    public static boolean resolveDLSSGFunctions() {
        try (Arena arena = Arena.ofConfined()) {
            // 解析 slDLSSGSetOptions
            MemorySegment funcName = arena.allocateFrom("slDLSSGSetOptions");
            MemorySegment funcPtr = arena.allocate(ValueLayout.ADDRESS);
            int result = slGetFeatureFunction(FEATURE_DLSS_G, funcName, funcPtr);
            if (result != RESULT_OK) {
                LOGGER.warning("Failed to resolve slDLSSGSetOptions: " + result);
                return false;
            }
            mhSlDLSSGSetOptions = Linker.nativeLinker().downcallHandle(
                funcPtr.get(ValueLayout.ADDRESS, 0),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );

            // 解析 slDLSSGGetState
            funcName = arena.allocateFrom("slDLSSGGetState");
            result = slGetFeatureFunction(FEATURE_DLSS_G, funcName, funcPtr);
            if (result == RESULT_OK) {
                mhSlDLSSGGetState = Linker.nativeLinker().downcallHandle(
                    funcPtr.get(ValueLayout.ADDRESS, 0),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                );
            }

            LOGGER.info("DLSS-G feature functions resolved successfully");
            return true;
        } catch (Exception e) {
            LOGGER.severe("Failed to resolve DLSS-G functions: " + e.getMessage());
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查 Result 是否为成功
     */
    public static boolean isOk(int result) {
        return result == RESULT_OK;
    }

    /**
     * 获取 Result 的描述
     */
    public static String getResultDescription(int result) {
        return switch (result) {
            case RESULT_OK -> "OK";
            case RESULT_ERROR_IO -> "IO Error";
            case RESULT_ERROR_DRIVER_OUT_OF_DATE -> "Driver Out of Date";
            case RESULT_ERROR_NO_SUPPORTED_ADAPTER_FOUND -> "No Supported Adapter Found";
            case RESULT_ERROR_VULKAN_API -> "Vulkan API Error";
            case RESULT_ERROR_NGX_FAILED -> "NGX Failed";
            case RESULT_ERROR_NOT_INITIALIZED -> "Not Initialized";
            case RESULT_ERROR_FEATURE_MISSING -> "Feature Missing";
            case RESULT_ERROR_FEATURE_NOT_SUPPORTED -> "Feature Not Supported";
            default -> "Unknown Error (" + result + ")";
        };
    }

    private static MethodHandle resolve(String functionName) {
        return streamlineLookup.find(functionName)
            .map(addr -> Linker.nativeLinker().downcallHandle(
                addr,
                getFunctionDescriptor(functionName)
            ))
            .orElseThrow(() -> new SLException("Failed to resolve function: " + functionName));
    }

    /**
     * 获取函数描述符
     * <p>
     * 基于 sl_core_api.h 中的函数签名定义。
     * 注意：Streamline 使用 C++ 命名空间和引用，
     * 在 FFM 层面引用被转换为指针。
     */
    private static FunctionDescriptor getFunctionDescriptor(String functionName) {
        return switch (functionName) {
            // slInit(const sl::Preferences& pref, uint64_t sdkVersion) -> sl::Result
            case "slInit" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG);

            // slShutdown() -> sl::Result
            case "slShutdown" -> FunctionDescriptor.of(ValueLayout.JAVA_INT);

            // slIsFeatureSupported(sl::Feature feature, const sl::AdapterInfo& adapterInfo) -> sl::Result
            case "slIsFeatureSupported" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slIsFeatureLoaded(sl::Feature feature, bool& loaded) -> sl::Result
            case "slIsFeatureLoaded" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slSetFeatureLoaded(sl::Feature feature, bool loaded) -> sl::Result
            case "slSetFeatureLoaded" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_BOOLEAN);

            // slGetNewFrameToken(sl::FrameToken*& token, const uint32_t* frameIndex) -> sl::Result
            case "slGetNewFrameToken" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            // slSetTagForFrame(const sl::FrameToken& frame, const sl::ViewportHandle& viewport,
            //                   const sl::ResourceTag* resources, uint32_t numResources,
            //                   sl::CommandBuffer* cmdBuffer) -> sl::Result
            case "slSetTagForFrame" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slSetConstants(const sl::Constants& values, const sl::FrameToken& frame,
            //                const sl::ViewportHandle& viewport) -> sl::Result
            case "slSetConstants" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            // slEvaluateFeature(sl::Feature feature, const sl::FrameToken& frame,
            //                    const sl::BaseStructure** inputs, uint32_t numInputs,
            //                    sl::CommandBuffer* cmdBuffer) -> sl::Result
            case "slEvaluateFeature" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slAllocateResources(sl::CommandBuffer* cmdBuffer, sl::Feature feature,
            //                      const sl::ViewportHandle& viewport) -> sl::Result
            case "slAllocateResources" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slFreeResources(sl::Feature feature, const sl::ViewportHandle& viewport) -> sl::Result
            case "slFreeResources" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slGetFeatureRequirements(sl::Feature feature, sl::FeatureRequirements& requirements) -> sl::Result
            case "slGetFeatureRequirements" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slGetFeatureVersion(sl::Feature feature, sl::FeatureVersion& version) -> sl::Result
            case "slGetFeatureVersion" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            // slGetFeatureFunction(sl::Feature feature, const char* functionName, void*& function) -> sl::Result
            case "slGetFeatureFunction" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

            // slSetVulkanInfo(const sl::VulkanInfo& info) -> sl::Result
            case "slSetVulkanInfo" -> FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS);

            default -> throw new SLException("Unknown function: " + functionName);
        };
    }

    private static void checkLoaded() {
        if (!loaded) {
            throw new SLException("Streamline SDK not loaded - call load() first");
        }
    }

    /**
     * Streamline 异常
     */
    public static final class SLException extends RuntimeException {
        public SLException(String message) {
            super(message);
        }

        public SLException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
