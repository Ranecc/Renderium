// Renderium Accelerator - Vec3 标量实现
// GPU友好: 无分支、无除法、无sqrt(除normalize外)
#include "accel_config.h"
#include <cmath>
#include <cstring>

namespace renderium { namespace accel { namespace math {

struct Vec3 {
    f32 x, y, z;

    Vec3() : x(0), y(0), z(0) {}
    Vec3(f32 x_, f32 y_, f32 z_) : x(x_), y(y_), z(z_) {}

    Vec3 operator+(const Vec3& b) const { return {x + b.x, y + b.y, z + b.z}; }
    Vec3 operator-(const Vec3& b) const { return {x - b.x, y - b.y, z - b.z}; }
    Vec3 operator*(f32 s) const { return {x * s, y * s, z * s}; }
    Vec3 operator*(const Vec3& b) const { return {x * b.x, y * b.y, z * b.z}; }
    Vec3& operator+=(const Vec3& b) { x += b.x; y += b.y; z += b.z; return *this; }
    Vec3& operator-=(const Vec3& b) { x -= b.x; y -= b.y; z -= b.z; return *this; }

    f32 dot(const Vec3& b) const { return x * b.x + y * b.y + z * b.z; }
    Vec3 cross(const Vec3& b) const {
        return {y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x};
    }
    f32 lengthSq() const { return x * x + y * y + z * z; }
    f32 length() const { return std::sqrt(lengthSq()); }

    Vec3 normalized() const {
        f32 lenSq = lengthSq();
        f32 invLen = (lenSq > 1e-12f) ? (1.0f / std::sqrt(lenSq)) : 0.0f;
        return {x * invLen, y * invLen, z * invLen};
    }

    Vec3 lerp(const Vec3& b, f32 t) const {
        return {x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t};
    }

    static Vec3 zero() { return {0, 0, 0}; }
    static Vec3 one() { return {1, 1, 1}; }
    static Vec3 unitX() { return {1, 0, 0}; }
    static Vec3 unitY() { return {0, 1, 0}; }
    static Vec3 unitZ() { return {0, 0, 1}; }
};

}}} 
