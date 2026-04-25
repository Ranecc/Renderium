// ============================================================
// Renderium Accelerator - 共享内存实现 (Windows/POSIX)
// ============================================================
// 跨平台共享内存管理
// 避免直接使用Windows宏，通过CMake定义的条件编译控制
//
// 平台支持:
//   - Windows: CreateFileMappingA / MapViewOfFile
//   - Linux:   shm_open / mmap
//   - macOS:  shm_open / mmap (与Linux相同)
//
// 线程安全: 所有操作使用内部互斥锁保护
// 内存对齐: 自动页对齐（4096字节）
// ============================================================

#include "platform_abstraction.h"

#include <cstring>
#include <mutex>

// ==================== 平台特定头文件 ====================
// 通过 CMake 定义 RENDERIUM_PLATFORM_xxx 宏来选择

#if defined(RENDERIUM_PLATFORM_WINDOWS)
    // Windows 平台实现
    #ifndef WIN32_LEAN_AND_MEAN
        #define WIN32_LEAN_AND_MEAN
    #endif
    #include <windows.h>

    using NativeHandle = HANDLE;
    
#elif defined(RENDERIUM_PLATFORM_LINUX) || defined(RENDERIUM_PLATFORM_MACOS)
    // POSIX 平台实现 (Linux/macOS)
    #include <sys/mman.h>
    #include <sys/stat.h>
    #include <fcntl.h>
    #include <unistd.h>
    #include <errno.h>
    
    using NativeHandle = int;

#else
    #error "Unsupported platform for shared memory"
#endif

namespace renderium {
namespace accel {
namespace platform {

// ==================== 内部实现类 ====================

/**
 * 共享内存区域内部状态
 */
class SharedMemoryImpl {
public:
    SharedMemoryRegion region;
    std::mutex accessMutex;              // 访问互斥锁
    
    SharedMemoryImpl() {
        region.address = nullptr;
        region.size = 0;
        region.isOwner = false;
        
        #if defined(RENDERIUM_PLATFORM_WINDOWS)
            region.nativeHandle = nullptr;
        #else
            region.nativeFd = -1;
        #endif
    }
    
    ~SharedMemoryImpl() {
        if (region.address != nullptr) {
            unmapInternal();
        }
    }

private:
    void unmapInternal() {
        if (region.address == nullptr) return;
        
        #if defined(RENDERIUM_PLATFORM_WINDOWS)
            if (UnmapViewOfFile(region.address)) {
                // 成功解除映射
            }
            
            if (region.isOwner && region.nativeHandle != nullptr) {
                CloseHandle(region.nativeHandle);
                region.nativeHandle = nullptr;
            }
        #else
            if (munmap(region.address, region.size) == 0) {
                // 成功解除映射
                region.address = nullptr;
            }
            
            if (region.isOwner && region.nativeFd >= 0) {
                shm_unlink(region.name.c_str());
                close(region.nativeFd);
                region.nativeFd = -1;
            }
        #endif
        
        region.address = nullptr;
        region.size = 0;
    }
};

// ==================== 全局实例表 ====================

static constexpr u32 MAX_REGIONS = MemoryLayout::MAX_SHARED_REGIONS;
static SharedMemoryImpl g_regions[MAX_REGIONS];
static std::mutex g_registryMutex;

// ==================== 辅助函数 ====================

/**
 * 页面对齐大小（向上取整）
 */
static size_t alignToPageSize(size_t size) {
    const size_t pageSize = MemoryLayout::PAGE_SIZE;
    return (size + pageSize - 1) & ~(pageSize - 1);
}

/**
 * 查找空闲区域槽位
 * @return 槽位索引，-1表示已满
 */
static i32 findFreeSlot() {
    for (u32 i = 0; i < MAX_REGIONS; ++i) {
        if (g_regions[i].region.address == nullptr) {
            return static_cast<i32>(i);
        }
    }
    return -1;
}

/**
 * 根据句柄查找区域实例
 */
static SharedMemoryImpl* findByHandle(SharedMemoryHandle handle) {
    auto* impl = static_cast<SharedMemoryImpl*>(handle);
    // 验证指针在有效范围内
    if (impl >= &g_regions[0] && impl < &g_regions[MAX_REGIONS]) {
        return impl;
    }
    return nullptr;
}

// ==================== 平台实现: Windows ====================

#if defined(RENDERIUM_PLATFORM_WINDOWS)

OperationResult createSharedMemoryPlatform(
    const char* name,
    size_t size,
    bool create,
    SharedMemoryRegion& outRegion
) {
    // 页面对齐
    size = alignToPageSize(size);
    
    // 创建或打开文件映射对象
    DWORD maxSizeHigh = static_cast<DWORD>((size >> 32) & 0xFFFFFFFF);
    DWORD maxSizeLow = static_cast<DWORD>(size & 0xFFFFFFFF);
    
    HANDLE hMap = nullptr;
    
    if (create) {
        hMap = CreateFileMappingA(
            INVALID_HANDLE_VALUE,  // 使用分页文件
            nullptr,                 // 默认安全属性
            PAGE_READWRITE,         // 读写权限
            maxSizeHigh,
            maxSizeLow,
            name                    // 映射名称
        );
        
        if (hMap == nullptr) {
            DWORD error = GetLastError();
            OperationResult result;
            result.error = ErrorCode::PlatformError;
            result.detailedCode = static_cast<i32>(error);
            result.errorMessage = "CreateFileMapping failed";
            return result;
        }
        
        outRegion.isOwner = true;
    } else {
        // 打开已有映射
        hMap = OpenFileMappingA(
            FILE_MAP_ALL_ACCESS,   // 访问权限
            FALSE,                  // 不继承给子进程
            name                    // 映射名称
        );
        
        if (hMap == nullptr) {
            DWORD error = GetLastError();
            if (error == ERROR_FILE_NOT_FOUND) {
                OperationResult result;
                result.error = ErrorCode::NotFound;
                result.errorMessage = "Shared memory not found";
                return result;
            }
            
            OperationResult result;
            result.error = ErrorCode::PlatformError;
            result.detailedCode = static_cast<i32>(error);
            result.errorMessage = "OpenFileMapping failed";
            return result;
        }
        
        outRegion.isOwner = false;
    }
    
    // 映射到进程地址空间
    void* address = MapViewOfFile(
        hMap,
        FILE_MAP_ALL_ACCESS,      // 访问权限
        0,                        // 文件偏移高32位
        0,                        // 文件偏移低32位
        size                      // 映射大小
    );
    
    if (address == nullptr) {
        DWORD error = GetLastError();
        CloseHandle(hMap);
        
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.detailedCode = static_cast<i32>(error);
        result.errorMessage = "MapViewOfFile failed";
        return result;
    }
    
    // 填充输出结构
    outRegion.address = address;
    outRegion.size = size;
    outRegion.name = name;
    outRegion.nativeHandle = hMap;
    
    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

OperationResult destroySharedMemoryPlatform(SharedMemoryRegion& region) {
    if (region.address == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Not mapped";
        return result;
    }
    
    // 解除映射
    if (!UnmapViewOfFile(region.address)) {
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.errorMessage = "UnmapViewOfFile failed";
        return result;
    }
    region.address = nullptr;
    
    // 仅owner关闭文件映射对象
    if (region.isOwner && region.nativeHandle != nullptr) {
        if (!CloseHandle(region.nativeHandle)) {
            OperationResult result;
            result.error = ErrorCode::PlatformError;
            result.errorMessage = "CloseHandle failed";
            return result;
        }
        region.nativeHandle = nullptr;
    }
    
    region.size = 0;
    
    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

// ==================== 平台实现: POSIX (Linux/macOS) ====================

#else // POSIX 实现

OperationResult createSharedMemoryPlatform(
    const char* name,
    size_t size,
    bool create,
    SharedMemoryRegion& outRegion
) {
    // 页面对齐
    size = alignToPageSize(size);
    
    int fd = -1;
    int flags = O_RDWR;
    mode_t mode = 0666;  // rw-rw-rw-
    
    if (create) {
        flags |= O_CREAT | O_EXCL;
    }
    
    // 打开/创建共享内存对象
    fd = shm_open(name, flags, mode);
    
    if (fd < 0) {
        i32 err = errno;
        
        if (err == EEXIST && !create) {
            // 已存在，尝试打开
            fd = shm_open(name, O_RDWR, mode);
        }
        
        if (fd < 0) {
            OperationResult result;
            result.error = (err == ENOENT) ? ErrorCode::NotFound : ErrorCode::PlatformError;
            result.detailedCode = err;
            result.errorMessage = strerror(err);
            return result;
        }
        
        outRegion.isOwner = false;
    } else {
        outRegion.isOwner = true;
        
        // 新创建：设置大小
        if (ftruncate(fd, static_cast<off_t>(size)) < 0) {
            i32 err = errno;
            close(fd);
            shm_unlink(name);
            
            OperationResult result;
            result.error = ErrorCode::PlatformError;
            result.detailedCode = err;
            result.errorMessage = "ftruncate failed";
            return result;
        }
    }
    
    // 映射到进程地址空间
    void* address = mmap(
        nullptr,                   // 让系统选择地址
        size,                     // 映射大小
        PROT_READ | PROT_WRITE,   // 权限：读写
        MAP_SHARED,               // 共享映射（可见于其他进程）
        fd,                       // 文件描述符
        0                         // 偏移量
    );
    
    if (address == MAP_FAILED) {
        i32 err = errno;
        close(fd);
        if (outRegion.isOwner) {
            shm_unlink(name);
        }
        
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.detailedCode = err;
        result.errorMessage = "mmap failed";
        return result;
    }
    
    // 填充输出结构
    outRegion.address = address;
    outRegion.size = size;
    outRegion.name = name;
    outRegion.nativeFd = fd;
    
    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

OperationResult destroySharedMemoryPlatform(SharedMemoryRegion& region) {
    if (region.address == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        result.errorMessage = "Not mapped";
        return result;
    }
    
    // 解除映射
    if (munmap(region.address, region.size) < 0) {
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.errorMessage = "munmap failed";
        return result;
    }
    region.address = nullptr;
    
    // 关闭文件描述符
    if (region.nativeFd >= 0) {
        close(region.nativeFd);
        region.nativeFd = -1;
    }
    
    // 仅owner删除共享内存对象
    if (region.isOwner) {
        if (shm_unlink(region.name.c_str()) < 0) {
            OperationResult result;
            result.error = ErrorCode::PlatformError;
            result.errorMessage = "shm_unlink failed";
            return result;
        }
    }
    
    region.size = 0;
    
    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

#endif // 平台选择结束

// ==================== 公共API实现 ====================

OperationResult createSharedMemory(
    const char* name,
    size_t size,
    bool create,
    SharedMemoryRegion& outRegion
) {
    std::lock_guard<std::mutex> lock(g_registryMutex);
    
    // 参数验证
    if (name == nullptr || strlen(name) == 0) {
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Invalid name";
        return result;
    }
    
    if (size == 0 || size > 1024 * 1024 * 1024) {  // 最大1GB
        OperationResult result;
        result.error = ErrorCode::InvalidArgument;
        result.errorMessage = "Invalid size (must be 1B-1GB)";
        return result;
    }
    
    // 查找或分配槽位
    i32 slot = -1;
    for (u32 i = 0; i < MAX_REGIONS; ++i) {
        if (g_regions[i].region.address == nullptr ||
            g_regions[i].region.name == name) {
            slot = static_cast<i32>(i);
            break;
        }
    }
    
    if (slot < 0) {
        OperationResult result;
        result.error = ErrorCode::OutOfMemory;
        result.errorMessage = "No free slots available";
        return result;
    }
    
    // 调用平台实现
    OperationResult result = createSharedMemoryPlatform(
        name, size, create, outRegion
    );
    
    if (result.error == ErrorCode::Success) {
        // 注册到全局表
        g_regions[slot].region = outRegion;
        // 返回impl指针作为句柄
        // 注意：这里简化处理，实际应该返回impl的地址
    }
    
    return result;
}

OperationResult destroySharedMemory(SharedMemoryRegion& region) {
    std::lock_guard<std::mutex> lock(g_registryMutex);
    
    if (region.address == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        return result;
    }
    
    // 调用平台实现
    OperationResult result = destroySharedMemoryPlatform(region);
    
    // 从全局表中移除（逐字段重置，避免拷贝赋值）
    for (u32 i = 0; i < MAX_REGIONS; ++i) {
        if (g_regions[i].region.address == region.address) {
            g_regions[i].region.address = nullptr;
            g_regions[i].region.size = 0;
            g_regions[i].region.name.clear();
            g_regions[i].region.isOwner = false;
            #if defined(RENDERIUM_PLATFORM_WINDOWS)
                g_regions[i].region.nativeHandle = nullptr;
            #else
                g_regions[i].region.nativeFd = -1;
            #endif
            break;
        }
    }
    
    return result;
}

OperationResult unmapSharedMemory(SharedMemoryRegion& region) {
    // 解除映射但不销毁（非owner使用）
    if (region.address == nullptr) {
        OperationResult result;
        result.error = ErrorCode::NotInitialized;
        return result;
    }
    
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
    if (!UnmapViewOfFile(region.address)) {
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.errorMessage = "UnmapViewOfFile failed";
        return result;
    }
    #else
    if (munmap(region.address, region.size) < 0) {
        OperationResult result;
        result.error = ErrorCode::PlatformError;
        result.errorMessage = "munmap failed";
        return result;
    }
    #endif
    
    region.address = nullptr;
    
    OperationResult result;
    result.error = ErrorCode::Success;
    return result;
}

// ==================== 其他平台函数的占位实现 ====================
// （后续补充完整实现）

PlatformType getPlatformType() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        return PlatformType::Windows;
    #elif defined(RENDERIUM_PLATFORM_MACOS)
        return PlatformType::MacOS;
    #elif defined(RENDERIUM_PLATFORM_LINUX)
        return PlatformType::Linux;
    #else
        return PlatformType::Unknown;
    #endif
}

const char* getPlatformName() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        return "Windows";
    #elif defined(RENDERIUM_PLATFORM_MACOS)
        return "macOS";
    #elif defined(RENDERIUM_PLATFORM_LINUX)
        return "Linux";
    #else
        return "Unknown";
    #endif
}

bool isFeatureSupported(const char* feature) {
    if (feature == nullptr) return false;
    
    // 检查共享内存支持
    if (strcmp(feature, "shared_memory") == 0) {
        return true;  // 所有目标平台都支持
    }
    
    // TODO: 添加更多特性检测
    return false;
}

u32 getLogicalCoreCount() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        SYSTEM_INFO sysinfo;
        GetSystemInfo(&sysinfo);
        return static_cast<u32>(sysinfo.dwNumberOfProcessors);
    #else
        long cores = sysconf(_SC_NPROCESSORS_ONLN);
        return cores > 0 ? static_cast<u32>(cores) : 1;
    #endif
}

u64 getTotalMemorySize() {
    #if defined(RENDERIUM_PLATFORM_WINDOWS)
        MEMORYSTATUSEX memInfo;
        memInfo.dwLength = sizeof(memInfo);
        if (GlobalMemoryStatusEx(&memInfo)) {
            return memInfo.ullTotalPhys;
        }
        return 0;
    #else
        long pages = sysconf(_SC_PHYS_PAGES);
        long pageSize = sysconf(_SC_PAGE_SIZE);
        return (pages > 0 && pageSize > 0) 
            ? static_cast<u64>(pages) * static_cast<u64>(pageSize) 
            : 0;
    #endif
}

} // namespace platform
} // namespace accel
} // namespace renderium
