#ifndef GEOMETRIC_CAMERA_H
#define GEOMETRIC_CAMERA_H

#include <vector>
#include <cmath>
#include <opencv2/core/core.hpp>

class GeometricCamera {
public:
    virtual ~GeometricCamera() {}
    virtual cv::Point2f Project(const cv::Point3f &p3D) = 0;
    virtual cv::Point3f Unproject(const cv::Point2f &p2D) = 0;
};

class CubeMapCamera : public GeometricCamera {
public:
    CubeMapCamera(float width, float height) : w(width), h(height) {
        // Assume 90 degree FOV per face
        fx = w / 2.0f;
        fy = h / 2.0f;
        cx = w / 2.0f;
        cy = h / 2.0f;
    }

    // 0: Right (+X), 1: Left (-X), 2: Top (+Y), 3: Bottom (-Y), 4: Front (+Z), 5: Back (-Z)
    int GetFace(const cv::Point3f &p3D) {
        float absX = std::abs(p3D.x);
        float absY = std::abs(p3D.y);
        float absZ = std::abs(p3D.z);

        if (absX >= absY && absX >= absZ) {
            return (p3D.x > 0) ? 0 : 1;
        } else if (absY >= absX && absY >= absZ) {
            return (p3D.y > 0) ? 2 : 3;
        } else {
            return (p3D.z > 0) ? 4 : 5;
        }
    }

    cv::Point2f Project(const cv::Point3f &p3D) override {
        // 1. Determine Face
        int face = GetFace(p3D);

        // 2. Rotate to Face Local Frame (OpenGL Cubemap convention)
        // Note: OpenGL cubemap convention:
        // +X (0): sc=-z, tc=-y, ma=x
        // -X (1): sc=z,  tc=-y, ma=abs(x)
        // +Y (2): sc=x,  tc=z,  ma=y
        // -Y (3): sc=x,  tc=-z, ma=abs(y)
        // +Z (4): sc=x,  tc=-y, ma=z
        // -Z (5): sc=-x, tc=-y, ma=abs(z)

        cv::Point3f pLocal;
        if (face == 0)      pLocal = cv::Point3f(-p3D.z, -p3D.y, p3D.x);
        else if (face == 1) pLocal = cv::Point3f(p3D.z,  -p3D.y, -p3D.x);
        else if (face == 2) pLocal = cv::Point3f(p3D.x,  p3D.z,  p3D.y);
        else if (face == 3) pLocal = cv::Point3f(p3D.x,  -p3D.z, -p3D.y);
        else if (face == 4) pLocal = cv::Point3f(p3D.x,  -p3D.y, p3D.z);
        else if (face == 5) pLocal = cv::Point3f(-p3D.x, -p3D.y, -p3D.z);

        // 3. Pinhole Projection
        // pLocal.z here corresponds to the major axis 'ma' in OpenGL terms
        // pLocal.x corresponds to 'sc', pLocal.y corresponds to 'tc'
        // But in pinhole math: u = fx * (x/z) + cx.
        // So we treat pLocal.x as x, pLocal.y as y, pLocal.z as depth (z).
        float invZ = 1.0f / std::abs(pLocal.z);

        float u = fx * (pLocal.x * invZ) + cx;
        float v = fy * (pLocal.y * invZ) + cy;

        return cv::Point2f(u, v);
    }

    cv::Point3f Unproject(const cv::Point2f &p2D) override {
        // Need Face ID context, default stub
        return cv::Point3f(0,0,0);
    }

    cv::Point3f Unproject(const cv::Point2f &p2D, int face) {
        float u = p2D.x;
        float v = p2D.y;

        // Normalized device coordinates (-1 to 1 approx, scaled by focal length)
        // x = (u - cx) / fx
        // y = (v - cy) / fy
        float x = (u - cx) / fx;
        float y = (v - cy) / fy;
        float z = 1.0f; // Represents the major axis plane distance

        // Inverse of the Face Rotation
        // We need to map (x, y, z) back to global (X, Y, Z)
        // Based on the Project mapping:
        // 0 (+X): x=-Z, y=-Y, z=X -> X=z, Y=-y, Z=-x
        // 1 (-X): x=Z, y=-Y, z=-X -> X=-z, Y=-y, Z=x
        // 2 (+Y): x=X, y=Z, z=Y   -> X=x, Y=z, Z=y
        // 3 (-Y): x=X, y=-Z, z=-Y -> X=x, Y=-z, Z=-y
        // 4 (+Z): x=X, y=-Y, z=Z  -> X=x, Y=-y, Z=z
        // 5 (-Z): x=-X, y=-Y, z=-Z-> X=-x, Y=-y, Z=-z

        cv::Point3f pGlobal;

        if (face == 0)      pGlobal = cv::Point3f(z, -y, -x);
        else if (face == 1) pGlobal = cv::Point3f(-z, -y, x);
        else if (face == 2) pGlobal = cv::Point3f(x, z, y);
        else if (face == 3) pGlobal = cv::Point3f(x, -z, -y);
        else if (face == 4) pGlobal = cv::Point3f(x, -y, z);
        else if (face == 5) pGlobal = cv::Point3f(-x, -y, -z);

        // Normalize to unit sphere
        float norm = std::sqrt(pGlobal.x*pGlobal.x + pGlobal.y*pGlobal.y + pGlobal.z*pGlobal.z);
        return cv::Point3f(pGlobal.x/norm, pGlobal.y/norm, pGlobal.z/norm);
    }

private:
    float w, h;
    float fx, fy, cx, cy;
};

#endif // GEOMETRIC_CAMERA_H
