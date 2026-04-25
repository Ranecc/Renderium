# ============================================================
# Renderium Accelerator - 纯计算加速库 CMake 配置
# ============================================================
# 目标: 生成动态库供 Java Panama FFM 调用
#
# 编译选项:
#   cmake -B build -G "Visual Studio 17 2022" -A x64 .
#   cmake --build build --config Release
#
#   # Linux/macOS:
#   cmake -B build -DCMAKE_BUILD_TYPE=Release .
#   cmake --build build -j$(nproc)
# ============================================================

cmake_minimum_required(VERSION 3.20)
project(RenderiumAccelerator
    VERSION 1.0.0
    DESCRIPTION "High-performance computation library for Renderium (no GPU dependency)"
    LANGUAGES CXX
)

# ==================== C++ 标准要求 ====================
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ==================== 平台检测与配置 ====================

if(WIN32)
    set(PLATFORM_NAME "Windows")
    add_compile_definitions(RENDERIUM_PLATFORM_WINDOWS)
    add_compile_definitions(RENDERIUM_ACCEL_EXPORTS)
    
    # MSVC 编译选项
    if(MSVC)
        add_compile_options(
            /W3                  # 警告级别3
            /utf-8               # UTF-8 源文件编码
            /EHsc                # 异常处理同步
            /GR                  # 启用RTTI
            /Zc:__cplusplus      # 严格的__cplusplus宏
        )
        
        # Release优化
        if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
            add_compile_options(
                /O2                  # 优化级别2
                /Ob2                 # 内联扩展
                /GF                  # 消除重复字符串
                /Gy                  # 函数级链接
            )
            add_link_options(/LTCG) # 链接时代码生成
        endif()
        
        # Debug信息
        if(CMAKE_BUILD_TYPE STREQUAL "Debug")
            add_compile_options(
                /Od                  # 禁用优化
                /Zi                  # 生成调试信息
                /MDd                 # 多线程调试DLL
            )
        endif()
    else()
        # MinGW-w64
        add_compile_options(-Wall -Wextra)
    endif()
    
elseif(APPLE)
    set(PLATFORM_NAME "macOS")
    add_compile_definitions(RENDERIUM_PLATFORM_MACOS)
    
    # Clang (Xcode)
    add_compile_options(
        -Wall -Wextra -Wpedantic
        -fcolor-diagnostics
    )
    
    if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
        add_compile_options(-O2 -march=native)
    endif()
    
else()
    # Linux / 其他Unix
    set(PLATFORM_NAME "Linux")
    add_compile_definitions(RENDERIUM_PLATFORM_LINUX)
    
    # GCC 或 Clang
    if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
        add_compile_options(
            -Wall -Wextra -Wpedantic -Wno-unused-parameter
            -fPIC                     # 位置无关代码（必需用于共享库）
        )
        
        if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
            add_compile_options(-O2 -march=native -flto) # 链接时优化
            add_link_options(-flto)
        endif()
        
        # Debug模式
        if(CMAKE_BUILD_TYPE STREQUAL "Debug")
            add_compile_options(-g -fsanitize=address -fno-omit-frame-pointer)
            add_link_options(-fsanitize=address)
        endif()
    endif()
endif()

message(STATUS "Platform: ${PLATFORM_NAME}")
message(STATUS "Compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")

# ==================== 源文件定义 ====================

# L0: 平台抽象层实现
set(PLATFORM_SOURCES
    src/platform/shared_memory.cpp
    src/platform/timer.cpp
    src/platform/cpu_info.cpp
    src/platform/atomic_ops.cpp
)

# L1: 数学基础库
set(MATH_SOURCES
    src/math/vec3.cpp
    src/math/mat4.cpp
    src/math/quaternion.cpp
    src/math/fast_math.cpp
)

# L2: 核心算法实现
set(ALGORITHM_SOURCES
    src/algorithms/bfs_occlusion.cpp
    src/algorithms/lyapunov_checker.cpp
    src/algorithms/lod_calculator.cpp
    src/algorithms/kahan_accumulator.cpp
    src/algorithms/convergence_monitor.cpp
)

# L3: FFI导出层
set(FFI_SOURCES
    src/ffi/library_init.cpp
    src/ffi/lod_ffi.cpp
    src/ffi/kahan_ffi.cpp
    src/ffi/convergence_ffi.cpp
)

# 合并所有源文件
set(ALL_SOURCES
    ${PLATFORM_SOURCES}
    ${MATH_SOURCES}
    ${ALGORITHM_SOURCES}
    ${FFI_SOURCES}
)

# ==================== 共享库目标 ====================

add_library(renderium_accel SHARED ${ALL_SOURCES})

# 设置输出名称
set_target_properties(renderium_accel PROPERTIES
    OUTPUT_NAME "renderium_accel"
    VERSION ${PROJECT_VERSION}
    SOVERSION 1
    POSITION_INDEPENDENT_CODE ON  # PIC for shared lib
)

# 包含目录
target_include_directories(renderium_accel
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/include>
        $<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/include/renderium/accel>
    PRIVATE
        $<BUILD_INTERFACE:${CMAKE_SOURCE_DIR}/src>
)

# 导出符号（仅导出ACCEL_API标记的函数）
if(WIN32)
    # Windows: 使用 .def 文件控制导出
    target_sources(renderium_accel PRIVATE renderium_accel.def)
else()
    # Unix: 使用版本脚本或编译器属性
    target_compile_definitions(renderium_accel PRIVATE RENDERIUM_ACCEL_EXPORTS)
endif()

# 链接选项
if(WIN32)
    # Windows链接器设置
    target_link_libraries(renderium_accel PRIVATE)
    
    # 延迟加载（减少启动时间）
    if(MSVC)
        set_target_properties(renderium_accel PROPERTIES
            LINK_FLAGS "/DELAYLOAD:NO"
        )
    endif()
else()
    # Unix链接器设置
    target_link_libraries(renderium_accel PRIVATE
        pthread          # POSIX线程库
        rt               # POSIX实时扩展（共享内存）
        dl               # 动态链接库加载
    )
endif()

# ==================== 安装规则 ====================

install(TARGETS renderium_accel
    RUNTIME DESTINATION bin
    LIBRARY DESTINATION lib
    ARCHIVE DESTINATION lib
)

# 安装头文件
install(DIRECTORY include/renderium
    DESTINATION include
    FILES_MATCHING PATTERN "*.h"
)

# ==================== 开发者辅助目标 ====================

# 运行测试（如果有）
option(BUILD_TESTS "Build unit tests" OFF)
if(BUILD_TESTS)
    enable_testing()
    
    find_package(GTest QUIET)
    if(NOT GTEST_FOUND)
        message(STATUS "GTest not found, skipping tests")
    else()
        # 测试源文件（如果存在）
        file(GLOB TEST_SOURCES "tests/*.cpp")
        
        if(TEST_SOURCES)
            foreach(test_src ${TEST_SOURCES})
                get_filename_component(test_name ${test_src} NAME_WE)
                
                add_executable(${test_name} ${test_src})
                target_link_libraries(${test_name} PRIVATE renderium_accel GTest::gtest)
                
                add_test(NAME ${test_name} COMMAND ${test_name})
            endforeach()
            
            message(STATUS "Tests enabled")
        endif()
    endif()
endif()

# 性能基准测试
option(BUILD_BENCHMARKS "Build performance benchmarks" OFF)
if(BUILD_BENCHMARKS)
    file(GLOB BENCH_SOURCES "benchmarks/*.cpp")
    
    if(BENCH_SOURCES)
        foreach(bench_src ${BENCH_SOURCES})
            get_filename_component(bench_name ${bench_src} NAME_WE)
            
            add_executable(${bench_name} ${bench_src})
            target_link_libraries(${bench_name} PRIVATE renderium_accel)
            
            if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
                target_compile_options(${bench_name} PRIVATE -O2 -march=native)
            endif()
        endforeach()
        
        message(STATUS "Benchmarks enabled")
    endif()
endif()

# ==================== 打包配置 ===================

include(CMakePackageConfigHelpers)

configure_package_config_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/renderium_accel-config.cmake.in"
    "${CMAKE_CURRENT_BINARY_DIR}/renderium_accel-config.cmake"
    INSTALL_DESTINATION lib/cmake/renderium_accel
)

write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/renderium_accel-config-version.cmake"
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY AnyNewerVersion
)

install(FILES
    "${CMAKE_CURRENT_BINARY_DIR}/renderium_accel-config.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/renderium_accel-config-version.cmake"
    DESTINATION lib/cmake/renderium_accel
)

# ==================== 总结 ====================

message(STATUS "")
message(STATUS "========== Renderium Accelerator Build Configuration ==========")
message(STATUS "  Version:     ${PROJECT_VERSION}")
message(STATUS "  Platform:    ${PLATFORM_NAME}")
message(STATUS "  Build Type:  ${CMAKE_BUILD_TYPE}")
message(STATUS "  Install Dir: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "  Sources:     ${ALL_SOURCES}")
message(STATUS "=============================================================")
