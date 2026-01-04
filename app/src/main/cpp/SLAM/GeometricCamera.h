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

    // Additional methods for Jacobians etc. would go here
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

    // 0: Right, 1: Left, 2: Top, 3: Bottom, 4: Front, 5: Back
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

        // 2. Rotate to Face Local Frame
        cv::Point3f pLocal;
        if (face == 0) pLocal = cv::Point3f(-p3D.z, -p3D.y, p3D.x);       // +X (Right)
        else if (face == 1) pLocal = cv::Point3f(p3D.z, -p3D.y, -p3D.x);  // -X (Left)
        else if (face == 2) pLocal = cv::Point3f(p3D.x, p3D.z, p3D.y);    // +Y (Top)
        else if (face == 3) pLocal = cv::Point3f(p3D.x, -p3D.z, -p3D.y);  // -Y (Bottom)
        else if (face == 4) pLocal = cv::Point3f(p3D.x, -p3D.y, p3D.z);   // +Z (Front)
        else if (face == 5) pLocal = cv::Point3f(-p3D.x, -p3D.y, -p3D.z); // -Z (Back)

        // 3. Pinhole Projection
        float invZ = 1.0f / pLocal.z;
        float u = fx * pLocal.x * invZ + cx;
        float v = fy * pLocal.y * invZ + cy;

        return cv::Point2f(u, v); // Note: This u,v is local to the face.
                                  // In a real system, we might need to encode face ID in the point
                                  // or handle it externally.
    }

    cv::Point3f Unproject(const cv::Point2f &p2D) override {
        // Need Face ID to unproject.
        // This signature assumes single image.
        // For CubeMap, we usually pass face ID or have 6 cameras.
        // This is a simplification.
        return cv::Point3f(0,0,0);
    }

    cv::Point3f Unproject(const cv::Point2f &p2D, int face) {
        float u = p2D.x;
        float v = p2D.y;

        float x = (u - cx) / fx;
        float y = (v - cy) / fy;
        float z = 1.0f;

        cv::Point3f pLocal(x, y, z);
        cv::Point3f pGlobal;

        if (face == 0) pGlobal = cv::Point3f(pLocal.z, -pLocal.y, -pLocal.x);
        else if (face == 1) pGlobal = cv::Point3f(-pLocal.z, -pLocal.y, pLocal.x);
        else if (face == 2) pGlobal = cv::Point3f(pLocal.x, pLocal.z, -pLocal.y); // Check rotation
        else if (face == 3) pGlobal = cv::Point3f(pLocal.x, -pLocal.z, -pLocal.y); // Check rotation
        else if (face == 4) pGlobal = cv::Point3f(pLocal.x, -pLocal.y, pLocal.z);
        else if (face == 5) pGlobal = cv::Point3f(-pLocal.x, -pLocal.y, -pLocal.z);

        // Normalize to unit sphere
        float norm = std::sqrt(pGlobal.x*pGlobal.x + pGlobal.y*pGlobal.y + pGlobal.z*pGlobal.z);
        return cv::Point3f(pGlobal.x/norm, pGlobal.y/norm, pGlobal.z/norm);
    }

private:
    float w, h;
    float fx, fy, cx, cy;
};

#endif // GEOMETRIC_CAMERA_H
