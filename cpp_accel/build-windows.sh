#!/bin/bash
# MinGW-w64 交叉编译 renderium_accel Windows DLL (适配当前项目结构)
set -e

SRC="/mnt/e/DEV/Renderium-shader/Renderium/cpp_accel"
BUILD="$SRC/build-windows-mingw"
DST="/mnt/e/DEV/Renderium-shader/Renderium/common/src/main/resources/native/windows-x64"

echo "========================================"
echo "  Cross-compiling for Windows x64"
echo "  Source: $SRC"
echo "  Output: $DST"
echo "========================================"

# 清理旧构建
rm -rf "$BUILD" 2>/dev/null
mkdir -p "$BUILD"
cd "$BUILD"

# CMake 配置
echo "[1/3] CMake Configure..."
cmake "$SRC" \
  -G "Unix Makefiles" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=Windows \
  -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
  -DCMAKE_C_COMPILER=/usr/bin/x86_64-w64-mingw32-gcc \
  -DCMAKE_CXX_COMPILER=/usr/bin/x86_64-w64-mingw32-g++ \
  -DCMAKE_RC_COMPILER=/usr/bin/x86_64-w64-mingw32-windres \
  -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY \
  -DCMAKE_SHARED_LINKER_FLAGS="-static-libgcc -static-libstdc++ -static" \
  2>&1 | tail -10

# 编译
echo ""
echo "[2/3] Building..."
make -j$(nproc) 2>&1 | tail -15

# 复制产物
echo ""
echo "[3/3] Copying to resources..."
mkdir -p "$DST"

DLL_FILE=$(find "$BUILD" -name "renderium_accel.dll" -type f 2>/dev/null | head -1)
if [ -n "$DLL_FILE" ]; then
    cp "$DLL_FILE" "$DST/"
    echo "  DLL: $(basename $DLL_FILE)"
    ls -lh "$DST/renderium_accel.dll"
    echo "DONE"
else
    LIB_DLL=$(find "$BUILD" -name "*.dll" -type f 2>/dev/null | head -1)
    if [ -n "$LIB_DLL" ]; then
        cp "$LIB_DLL" "$DST/"
        mv "$DST/$(basename $LIB_DLL)" "$DST/renderium_accel.dll" 2>/dev/null || true
        ls -lh "$DST/"*.dll 2>/dev/null
        echo "DONE"
    else
        echo "ERROR: No DLL found!"
        find "$BUILD" -name "*.dll" -o -name "*.so" -o -name "*.a" 2>/dev/null | head -5
        exit 1
    fi
fi
