// Renderium Accelerator - Mat4 标量实现 (列主序, 与Vulkan一致)
// GPU友好: 无分支、列主序存储、对齐16字节
#include "accel_config.h"
#include <cmath>
#include <cstring>

namespace renderium { namespace accel { namespace math {

struct Mat4 {
    alignas(16) f32 m[16];

    Mat4() { std::memset(m, 0, sizeof(m)); }

    static Mat4 identity() {
        Mat4 r;
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0f;
        return r;
    }

    static Mat4 perspective(f32 fovRad, f32 aspect, f32 nearZ, f32 farZ) {
        f32 f = 1.0f / std::tan(fovRad * 0.5f);
        f32 rangeInv = 1.0f / (nearZ - farZ);
        Mat4 r;
        r.m[0]  = f / aspect;
        r.m[5]  = f;
        r.m[10] = farZ * rangeInv;
        r.m[11] = -1.0f;
        r.m[14] = nearZ * farZ * rangeInv;
        return r;
    }

    static Mat4 orthographic(f32 left, f32 right, f32 bottom, f32 top, f32 nearZ, f32 farZ) {
        f32 rl = 1.0f / (right - left);
        f32 tb = 1.0f / (top - bottom);
        f32 fn = 1.0f / (farZ - nearZ);
        Mat4 r;
        r.m[0]  = 2.0f * rl;
        r.m[5]  = 2.0f * tb;
        r.m[10] = -2.0f * fn;
        r.m[12] = -(right + left) * rl;
        r.m[13] = -(top + bottom) * tb;
        r.m[14] = -(farZ + nearZ) * fn;
        r.m[15] = 1.0f;
        return r;
    }

    static Mat4 lookAt(f32 eyeX, f32 eyeY, f32 eyeZ,
                        f32 centerX, f32 centerY, f32 centerZ,
                        f32 upX, f32 upY, f32 upZ) {
        f32 fX = centerX - eyeX, fY = centerY - eyeY, fZ = centerZ - eyeZ;
        f32 fLen = 1.0f / std::sqrt(fX * fX + fY * fY + fZ * fZ);
        fX *= fLen; fY *= fLen; fZ *= fLen;

        f32 sX = fY * upZ - fZ * upY;
        f32 sY = fZ * upX - fX * upZ;
        f32 sZ = fX * upY - fY * upX;
        f32 sLen = 1.0f / std::sqrt(sX * sX + sY * sY + sZ * sZ);
        sX *= sLen; sY *= sLen; sZ *= sLen;

        f32 uX = sY * fZ - sZ * fY;
        f32 uY = sZ * fX - sX * fZ;
        f32 uZ = sX * fY - sY * fX;

        Mat4 r;
        r.m[0] = sX;  r.m[4] = sY;  r.m[8]  = sZ;  r.m[12] = -(sX * eyeX + sY * eyeY + sZ * eyeZ);
        r.m[1] = uX;  r.m[5] = uY;  r.m[9]  = uZ;  r.m[13] = -(uX * eyeX + uY * eyeY + uZ * eyeZ);
        r.m[2] = -fX; r.m[6] = -fY; r.m[10] = -fZ; r.m[14] = (fX * eyeX + fY * eyeY + fZ * eyeZ);
        r.m[3] = 0;   r.m[7] = 0;   r.m[11] = 0;   r.m[15] = 1.0f;
        return r;
    }

    Mat4 operator*(const Mat4& b) const {
        Mat4 r;
        for (int col = 0; col < 4; ++col) {
            for (int row = 0; row < 4; ++row) {
                r.m[col * 4 + row] =
                    m[row]      * b.m[col * 4]     +
                    m[4 + row]  * b.m[col * 4 + 1] +
                    m[8 + row]  * b.m[col * 4 + 2] +
                    m[12 + row] * b.m[col * 4 + 3];
            }
        }
        return r;
    }

    static Mat4 translation(f32 tx, f32 ty, f32 tz) {
        Mat4 r = identity();
        r.m[12] = tx; r.m[13] = ty; r.m[14] = tz;
        return r;
    }

    static Mat4 scaling(f32 sx, f32 sy, f32 sz) {
        Mat4 r;
        r.m[0] = sx; r.m[5] = sy; r.m[10] = sz; r.m[15] = 1.0f;
        return r;
    }
};

}}}
