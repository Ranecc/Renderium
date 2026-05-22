# shaders-src — GLSL Shader 源码

本目录存放 Renderium 渲染引擎的所有 GLSL shader 源码，按 DDD 分层组织。

> **注意**: 本目录进 Git，**不进 release JAR**。编译产物（.spv）位于同级 `shaders/` 目录。

## 目录结构

```
shaders-src/
├── pipeline/                 # 渲染管线节点
│   ├── geometry/             # 几何阶段（G-Buffer 填充等）
│   ├── material/             # 材质阶段（PBR 等）
│   ├── lighting/             # 光照阶段（阴影、SSAO、SSR 等）
│   └── postprocess/          # 后处理阶段
│       ├── bloom/            # 泛光
│       ├── tonemap/          # 色调映射
│       ├── antialiasing/     # 抗锯齿（TAA）
│       ├── effects/          # 特效（DOF、运动模糊、色差等）
│       ├── volumetric/       # 体积效果（体积雾）
│       ├── skybox/           # 天空盒
│       ├── reflection/       # 反射
│       └── rt/               # 光线追踪后处理
├── compute/                  # GPU 计算基础设施
│   ├── culling/              # 剔除（视锥、Hi-Z、LOD 等）
│   ├── collision/            # 碰撞检测
│   ├── lod/                  # LOD 计算
│   └── mesh/                 # 网格处理（Meshlet、顶点变换等）
├── raytracing/               # 光线追踪管线
│   ├── raygen/               # 光线生成
│   ├── closesthit/           # 最近命中
│   ├── anyhit/               # 任意命中
│   └── miss/                 # 未命中
└── common/                   # 公共库（include 用 .glsl）
```

## 编译流程

使用 `glslangValidator` 或 `glslc` 将 `.comp` 编译为 `.spv`：

```bash
# 单文件编译
glslc shaders-src/pipeline/geometry/gbuffer_fill.comp -o shaders/pipeline/geometry/gbuffer_fill.spv

# 批量编译（PowerShell）
Get-ChildItem -Recurse -Filter "*.comp" shaders-src/ | ForEach-Object {
    $relative = $_.FullName.Replace((Resolve-Path shaders-src/).Path + "\", "")
    $output = "shaders/" + $relative -replace '\.comp$', '.spv'
    $outDir = Split-Path $output
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    glslc $_.FullName -o $output
}
```

## 文件类型说明

| 扩展名 | 说明 | 编译目标 |
|--------|------|----------|
| `.comp` | Vulkan Compute Shader | `.spv` |
| `.vert` | Vertex Shader | `.spv` |
| `.frag` | Fragment Shader | `.spv` |
| `.shader` | Mesh Shader | `.spv` |
| `.raygen` / `.rchitglsl` / `.rahitglsl` / `.rmissglsl` | RT Shader | `.spv` |
| `.glsl` | 公共头文件（#include） | 不编译 |
