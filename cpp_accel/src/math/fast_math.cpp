// Renderium Accelerator - 快速数学运算
// 提供GPU友好的数学近似函数
// 使用memcpy避免严格别名违规(Strict Aliasing Rule)

#include "accel_config.h"

#include <cstring>

namespace renderium {
namespace accel {
namespace math {

f32 fastInvSqrt(f32 x) {
    float x2 = x * 0.5f;
    int i;
    std::memcpy(&i, &x, sizeof(i));
    i = 0x5f3759df - (i >> 1);
    std::memcpy(&x, &i, sizeof(x));
    x = x * (1.5f - (x2 * x * x));
    return x;
}

f32 fastSqrt(f32 x) {
    return x * fastInvSqrt(x);
}

f32 fastRecip(f32 x) {
    int i;
    std::memcpy(&i, &x, sizeof(i));
    i = 0x7ef311c2 - i;
    float r;
    std::memcpy(&r, &i, sizeof(r));
    return r * (2.0f - x * r);
}

} // namespace math
} // namespace accel
} // namespace renderium
