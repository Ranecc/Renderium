# Potential-Field-Guided Adaptive Ray Tracing: Engineering Constraints and Practical Feasibility

## Abstract

We present a potential-field-guided adaptive ray tracing system that combines raster-based annotations, 4D potential field solving, and AMR (Adaptive Mesh Refinement) to reduce ray tracing computation. Unlike previous work that presents such systems as conceptual architectures with optimistic performance projections, this paper focuses on **engineering constraints and failure modes**. We quantify six critical bottlenecks: memory bandwidth storms (12.4 GB/frame at 1080p), warp divergence from adaptive step sizes (60-75% efficiency), Hessian register pressure (24 registers/thread limiting occupancy to 75%), AMR cross-level ray leakage, Poisson solver overhead (2-4 ms/frame), and the logical contradiction between physically-accurate Rayleigh quotient guidance and stylized rendering. We propose mitigation strategies including BVH+Morton hybrid acceleration, temporal coherence reuse, and half-float compression, and provide the experimental framework for empirical validation on NVIDIA RTX 5060.

**Keywords**: ray tracing, potential field, adaptive mesh refinement, Vulkan compute shader, memory bandwidth, warp divergence

---

## 1. Introduction

### 1.1 Motivation

Real-time ray tracing remains computationally expensive even with dedicated hardware (RT Cores). The core insight of this work is that **not all rays require equal computational effort**: rays in empty space need large steps, rays near geometry boundaries need small steps, and rays hitting rough surfaces need fewer secondary rays than those hitting mirror surfaces.

A potential field U(x,y,z,t) solving the Poisson equation ∇²U = -ρ_s can encode "visual importance" across the scene, with source terms ρ_s derived from PBR material parameters, player position, and entity activity. The gradient ∇U guides rays toward important regions, while the Hessian matrix H drives adaptive step sizes via the Rayleigh quotient R(d) = dᵀHd/dᵀd.

### 1.2 Contributions (Honest Assessment)

1. **A unified mathematical framework** combining raster annotations, potential fields, AMR, and PBR baking — but we acknowledge this is primarily a conceptual contribution until empirically validated.

2. **Quantification of six engineering bottlenecks** that previous conceptual work has avoided discussing.

3. **Three mitigation strategies** with theoretical analysis of their trade-offs.

4. **An experimental framework** (GLSL compute shaders + Vulkan integration + Streamline SDK profiling) ready for empirical validation.

**What we do NOT claim**: We do not claim "6× speedup" or "240-500 FPS". Such claims without empirical data are irresponsible.

---

## 2. Mathematical Framework

### 2.1 4D Potential Field

The potential field U(x,y,z,t) satisfies the Poisson equation:

```
∇²U = -ρ_s
```

Source term decomposition:

```
ρ_s(x,t) = w_p·δ(x - x_player) + w_g·G(x, d_gaze) + w_v·|v|·δ(x - x_player)
          + Σ[w_r·(1-r) + w_m·m + w_e·e + w_n·|∇n|]  // PBR materials
          + Σw_a·δ(x - x_entity_i)                      // entities
```

### 2.2 Rayleigh Quotient Adaptive Step Size

The ODE for adaptive step size:

```
ds/dt = -α·R(d)·s + β·(s_target - s)
```

Stability condition: α·R(d) + β > 0 (always satisfied since R ≥ 0 for positive-semidefinite H).

Analytical solution: s(t) = s_target + (s₀ - s_target)·e^(-(αR+β)t)

### 2.3 Raster Annotations

G-Buffer output A(x) = {n(x), z(x), albedo(x), pbr(x)} provides:
- Depth z(x): occlusion-based ray skipping
- Normal n(x): reuse instead of RT estimation
- PBR parameters: modulate secondary ray count

---

## 3. Engineering Constraints (The Bad News)

### 3.1 Memory Bandwidth Storm

**Problem**: Each ray marching step reads potential field + gradient + Hessian + annotation data.

| Data | FP32 | FP16 |
|------|------|------|
| U (scalar) | 4B | 2B |
| ∇U (vec3) | 12B | 6B |
| H (symmetric 3×3) | 24B | 12B |
| Annotation A(x) | ~32B | ~32B |
| **Total/step/pixel** | **72B** | **52B** |

At 1080p with 128 average steps:
- FP32: 1920×1080 × 128 × 72B = **12.4 GB/frame**
- FP16: 1920×1080 × 128 × 52B = **8.9 GB/frame**

RTX 5060 (Blackwell) theoretical bandwidth: ~480 GB/s (GDDR7)

**Bandwidth-limited frame rate**:
- FP32: 480/12.4 ≈ **38.7 FPS** (upper bound!)
- FP16: 480/8.9 ≈ **53.9 FPS** (upper bound!)

This is the **hard ceiling** — actual FPS will be lower due to compute and divergence overhead.

### 3.2 Warp Divergence

**Problem**: Adaptive step sizes cause threads in the same warp (32 threads on NVIDIA) to take different numbers of iterations.

The worst case: half the warp finishes in 64 steps, the other half needs 128 steps. The early-finishing threads sit idle while waiting for the slow threads.

**Estimated warp efficiency**: 60-75% (based on step size variance analysis)

**Critical insight**: Fixed step size + annotation-based skipping may actually outperform adaptive step size in practice, because:
- Fixed step: all threads in lockstep → 90%+ warp efficiency
- Adaptive step: divergent paths → 60-75% warp efficiency
- The 25-40% divergence overhead may negate the 60% step reduction from adaptation

**This needs empirical validation. It is NOT obvious which approach wins.**

### 3.3 Hessian Register Pressure

Each thread must hold the 6-component symmetric Hessian matrix:

| Component | Registers |
|-----------|-----------|
| Hessian H (6×float32) | 6 |
| Gradient ∇U (3×float32) | 3 |
| Ray state (origin+dir+step+...) | ~10 |
| Other (counters, flags) | ~5 |
| **Total** | **~24** |

RTX 5060 (Blackwell SM): 65536 registers/SM, max 2048 threads/SM

- 24 registers/thread → 2048×24 = 49152 → **occupancy ≈ 75%**
- 32 registers/thread → 2048×32 = 65536 → **occupancy ≈ 50%** (dangerous!)

**Mitigation options**:
1. Store Hessian in shared memory instead of registers (increases shared memory pressure)
2. Use FP16 Hessian (6×2B = 12B, but requires conversion overhead)
3. Compute Rayleigh quotient on-the-fly without storing full Hessian (reduces register count to ~16)

### 3.4 AMR Cross-Level Ray Leakage

**Problem**: When a ray traverses from a coarse AMR cell (large step) to a fine cell, the large step may skip thin geometry (1-block walls, floors).

**Quantification**: With AMR level 4 (cell size = worldSize/16), a step of 2×cellSize can skip a 1-block wall.

**Solutions and their costs**:

| Solution | Cost | Effectiveness |
|----------|------|---------------|
| Conservative step clamping: step ≤ cellSize | Negates adaptive advantage | 100% |
| Boundary-aware stepping | +10% branch complexity | ~95% |
| BVH+Morton hybrid | +56MB memory, +BVH build time | ~99% |

We recommend the BVH+Morton hybrid (Section 4.1) as the best trade-off.

### 3.5 Poisson Solver Overhead

Multigrid V-Cycle on 64³ grid:
- 6 levels × (2 pre-smooth + 2 post-smooth) = 24 global Jacobi iterations
- Each iteration: 262144 cells × 6 neighbor reads = 1.57M reads
- Estimated GPU time: **2-4 ms/frame**

If the target frame budget is 16.67ms (60 FPS), spending 2-4ms on potential field solving alone is **12-24% of the frame budget** — significant but potentially acceptable.

**Mitigation**: Temporal coherence reuse (Section 4.2) reduces this to ~25% by updating only every 4th frame.

### 3.6 Stylization vs. Physical Accuracy Contradiction

The Rayleigh quotient R(d) = dᵀHd/dᵀd guides rays along the shortest path (Fermat's principle). The stylization function Φ(U, style) deliberately distorts ray paths for artistic effect.

These are **mutually exclusive objectives**:
- Physical mode: R(d) minimizes path length → accurate lighting
- Stylized mode: Φ distorts paths → artistic but physically incorrect

**Implementation**: These must be separate rendering modes with explicit switching, not a single "simultaneously solves both" system.

---

## 4. Mitigation Strategies

### 4.1 BVH + Morton Hybrid Acceleration

Two-layer structure:
1. **Morton Grid** (O(1) cell lookup): Quick rejection of empty cells
2. **Local BVH** (O(log M) traversal): Precise hit detection within occupied cells

Memory: ~56MB for 64³ grid with 10% occupancy (acceptable for 8GB VRAM)

Build time: O(M log M) per cell, M < 32 triangles → < 0.01ms per cell on GPU

### 4.2 Temporal Coherence Reuse

- Full Multigrid solve every 4 frames
- Incremental update (1 Jacobi iteration) on intermediate frames
- Motion compensation for player movement
- **Reduces Poisson overhead to ~25%**

### 4.3 Half-Float Potential Field

- FP16 for U and ∇U: safe (range and precision sufficient)
- FP16 for Hessian: **problematic** — mixed partial derivatives are small (~0.001) and may lose precision
- **Recommended**: Mixed precision — diagonal H components in FP32, off-diagonal in FP16
- **Bandwidth reduction**: ~40% (from 72B to ~44B per step per pixel)

---

## 5. Experimental Framework

### 5.1 Hardware

- **GPU**: NVIDIA RTX 5060 (Blackwell architecture)
- **VRAM**: 8GB GDDR7
- **Theoretical bandwidth**: ~480 GB/s
- **API**: Vulkan 1.3 + VK_KHR_ray_tracing_pipeline + VK_KHR_ray_query

### 5.2 Software Stack

| Component | Technology | File |
|-----------|-----------|------|
| Compute Shaders | GLSL → SPIR-V (shaderc) | `adaptive_ray_march.comp`, `potential_field_solver.comp` |
| Vulkan Integration | Panama FFM (Java 22+) | `VulkanComputeManager.java` |
| Performance Profiling | NVIDIA Streamline SDK v2.10.3 | `StreamlineIntegration.java` |
| Benchmark Framework | Custom Java | `RTBenchmarkRunner.java` |
| Acceleration Structure | BVH+Morton hybrid | `HybridBVHMorton.java` |
| Temporal Optimization | Incremental update + FP16 | `TemporalCoherenceManager.java` |

### 5.3 Three Test Modes

1. **SOFTWARE_ONLY**: Pure Compute Shader ray marching (no RT Core)
2. **HYBRID**: VK_KHR_ray_query inline ray tracing + annotation guidance
3. **HARDWARE_FULL**: VK_KHR_ray_tracing_pipeline with BLAS/TLAS/SBT

---

## 6. Expected Results (With Caveats)

**We explicitly state what we expect to find, and what could invalidate these expectations:**

| Metric | Expected Range | Could Be Wrong If |
|--------|---------------|-------------------|
| Pure software FPS (1080p) | 15-30 | Bandwidth is worse than estimated |
| Hybrid FPS | 40-80 | Ray query overhead is higher than expected |
| Hardware FPS | 80-160 | BLAS build time dominates |
| Warp efficiency (adaptive) | 60-75% | Step variance is higher than modeled |
| Warp efficiency (fixed) | 85-95% | Annotation skipping causes its own divergence |
| Bandwidth utilization | 60-80% of theoretical | Cache effects improve real throughput |
| AMR ray leak rate | 0.5-2% of rays | Depends on scene geometry |
| Poisson solver time | 2-4ms | Could be 6-8ms on complex scenes |

---

## 7. Related Work

| Work | Relation | Key Difference |
|------|----------|----------------|
| Neural Radiance Cache (Müller et al., 2021) | Most similar | Uses MLP, we use analytical Poisson |
| Q2VKPT / Q2RTX (NVIDIA) | Reference implementation | No potential field guidance |
| Lumen (UE5) | Industrial reference | Software/hybrid RT, no AMR |
| ReSTIR (NVIDIA) | Complementary | Could be combined with our system |
| Path Guiding (Vorba et al.) | Related | SDTree vs. potential field |

---

## 8. Conclusion

This paper presents a potential-field-guided adaptive ray tracing system and, more importantly, a **rigorous analysis of its engineering constraints**. The key findings are:

1. **Memory bandwidth is the primary bottleneck**, not compute. The 12.4 GB/frame bandwidth demand at 1080p FP32 limits the system to ~39 FPS even on RTX 5060.

2. **Warp divergence from adaptive step sizes may negate their theoretical advantage**. Fixed step + annotation skipping could be faster in practice.

3. **AMR cross-level ray leakage requires BVH backup**, adding ~56MB memory and build time overhead.

4. **The Poisson solver costs 2-4ms/frame**, which is significant but manageable with temporal coherence reuse.

5. **Stylized and physically-accurate rendering are mutually exclusive modes**, not simultaneously achievable goals.

**The honest assessment**: This system is a promising research direction, but it is NOT a "6× speedup middleware". It is a set of engineering trade-offs that must be carefully evaluated for each target scenario. The experimental framework we provide enables this evaluation with real data rather than optimistic projections.

---

## References

1. Müller, T., et al. "Real-time Neural Radiance Caching for Path Tracing." SIGGRAPH 2021.
2. NVIDIA. "Q2RTX: Quake II Ray Traced." GitHub, 2019.
3. Epic Games. "Lumen Technical Overview." Unreal Engine 5 Documentation, 2022.
4. Bitterli, B., et al. "ReSTIR: Reservoir-based Spatiotemporal Importance Resampling." SIGGRAPH 2020.
5. Vorba, J., et al. "On-line Learning of Parametric Mixture Models for Light Transport Simulation." SIGGRAPH 2014.
6. NVIDIA. "Streamline SDK v2.10.3." 2024.
7. Khronos Group. "VK_KHR_ray_tracing_pipeline." Vulkan Extension, 2020.
