// Renderium Accelerator - Quaternion 标量实现
// GPU友好: 无分支、归一化使用快速逆平方根
#include "accel_config.h"
#include <cmath>
#include <cstring>

namespace renderium { namespace accel { namespace math {

struct Quat {
    f32 w, x, y, z;

    Quat() : w(1), x(0), y(0), z(0) {}
    Quat(f32 w_, f32 x_, f32 y_, f32 z_) : w(w_), x(x_), y(y_), z(z_) {}

    Quat operator*(const Quat& q) const {
        return {
            w * q.w - x * q.x - y * q.y - z * q.z,
            w * q.x + x * q.w + y * q.z - z * q.y,
            w * q.y - x * q.z + y * q.w + z * q.x,
            w * q.z + x * q.y - y * q.x + z * q.w
        };
    }

    Quat conjugate() const { return {w, -x, -y, -z}; }

    f32 lengthSq() const { return w * w + x * x + y * y + z * z; }

    Quat normalized() const {
        f32 lenSq = lengthSq();
        f32 invLen = (lenSq > 1e-12f) ? (1.0f / std::sqrt(lenSq)) : 0.0f;
        return {w * invLen, x * invLen, y * invLen, z * invLen};
    }

    static Quat fromAxisAngle(f32 ax, f32 ay, f32 az, f32 angleRad) {
        f32 halfAngle = angleRad * 0.5f;
        f32 s = std::sin(halfAngle);
        f32 lenSq = ax * ax + ay * ay + az * az;
        f32 invLen = (lenSq > 1e-12f) ? (1.0f / std::sqrt(lenSq)) : 0.0f;
        return {std::cos(halfAngle), ax * invLen * s, ay * invLen * s, az * invLen * s};
    }

    static Quat slerp(const Quat& a, const Quat& b, f32 t) {
        f32 dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z;
        Quat bq = b;
        if (dot < 0.0f) { bq.w = -bq.w; bq.x = -bq.x; bq.y = -bq.y; bq.z = -bq.z; dot = -dot; }
        f32 theta = std::acos(dot < 1.0f ? dot : 1.0f);
        f32 sinTheta = std::sin(theta);
        f32 wa = (sinTheta > 1e-6f) ? (std::sin((1.0f - t) * theta) / sinTheta) : (1.0f - t);
        f32 wb = (sinTheta > 1e-6f) ? (std::sin(t * theta) / sinTheta) : t;
        return {wa * a.w + wb * bq.w, wa * a.x + wb * bq.x,
                wa * a.y + wb * bq.y, wa * a.z + wb * bq.z};
    }

    static Quat identity() { return {1, 0, 0, 0}; }
};

}}}
