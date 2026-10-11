// JNI bridge to the forked artoolkitX KPM tracker.
//
// ARCore remains an independent first-class pose source. This file exposes only the native KPM
// primitive used by the side-by-side :sphereslam library.
//
// IMPORTANT: artoolkitX 1.1.23's BINARY_FEATURE/FREAK matching path unconditionally runs its
// calibrated pose solve. kpmCreateHandleHomography() therefore leaves a null cparamLT that the
// matcher later dereferences. Sessions here always use kpmCreateHandle() with explicit intrinsics.
//
// Exceptions: every JNI entry point catches all C++ exceptions (std::bad_alloc from make_shared /
// map inserts / vectors, or anything artoolkitX's FREAK code throws) and returns its error value, so
// none unwinds through JNI. What cannot be caught: artoolkitX's own arMalloc() calls exit(1) when
// malloc fails, which terminates the process; that is upstream behaviour this bridge cannot change.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#define LOG_TAG "POSEPROBE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_ARX_KPM
#include <ARX/AR/ar.h>
#include <ARX/KPM/kpm.h>
// KpmHandle's private layout (freakMatcher) is needed to read the matched inlier correspondences.
#include <kpmPrivate.h>

// makeCameraParams() writes the OpenCV "version-5" dist_factor layout (fx,fy,cx,cy at indices [12..15],
// scale at [16]) but builds the ARParam with AR_DIST_FUNCTION_VERSION_DEFAULT. If that default is not
// version 5 the intrinsics land in the wrong slots and every KPM pose is solved with garbage
// calibration — silently. Fail the build loudly instead of shipping miscalibrated tracking.
static_assert(AR_DIST_FUNCTION_VERSION_DEFAULT == 5,
              "KpmBridge assumes the OpenCV v5 dist_factor layout; update makeCameraParams if the "
              "default artoolkitX distortion-function version changes.");

namespace {

struct KpmSession {
    KpmHandle *handle = nullptr;
    KpmRefDataSet *atlas = nullptr;
    ARParamLT *cameraParams = nullptr;
    int frameWidth = 0;
    int frameHeight = 0;
    // True once kpmSetRefDataSet has loaded the atlas into `handle`. The FREAK matcher inside a
    // handle cannot be re-loaded (VisualDatabase::addKeyframe throws "ID already exists" for a
    // reused image id), so a later add rebuilds the handle before indexing the grown atlas.
    bool indexed = false;
    // Page numbers of every atlas slot but the first, refreshed on every add (skipRedundantPoseSolves).
    std::vector<int> skipPages;
    // Lazily built single-page matchers over the same atlas features (focused matching). Built from
    // the already-extracted reference features, so they never re-run reference feature extraction.
    std::map<int, KpmHandle *> pageHandles;
    // Serializes every use of THIS session's handle/atlas (add, match, clone, clear). Different
    // sessions never share a lock, so a background atlas build on a cloned session cannot stall the
    // live matcher (the previous design held one global lock across kpmMatching).
    std::mutex work;

    ~KpmSession() {
        for (auto &entry : pageHandles) {
            if (entry.second) kpmDeleteHandle(&entry.second);
        }
        if (atlas) kpmDeleteRefDataSet(&atlas);
        if (handle) kpmDeleteHandle(&handle);
        if (cameraParams) arParamLTFree(&cameraParams);
    }
};

// artoolkitX logs an INFO line for every successful match (kpmMatching's "Page[n] pre: aft: error");
// at camera rate that floods logcat. Keep warnings and errors only.
void quietArtoolkitLogs() {
    static std::once_flag once;
    std::call_once(once, [] { arLogLevel = AR_LOG_LEVEL_WARN; });
}

// Lifetime guard. Sessions are addressed by a monotonically increasing id (never a raw pointer, so a
// freed-and-reused address can never alias a live session). A work function looks its session up
// under the briefly held registry lock, keeps the shared_ptr for the whole call and then takes only
// that session's `work` lock. nativeDestroySession unregisters the id; the native objects are freed
// by ~KpmSession when the last in-flight call drops its reference, so a destroy racing a call does
// not free objects that call is using (exercised by the instrumented KpmBridgeInstrumentedTest; the
// JVM tests cannot load this library), and different sessions are never serialized against each other.
std::mutex gKpmRegistryMutex;
std::unordered_map<jlong, std::shared_ptr<KpmSession>> gKpmSessions;
jlong gKpmNextId = 1;

std::shared_ptr<KpmSession> findSession(jlong id) {
    std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
    auto it = gKpmSessions.find(id);
    return it == gKpmSessions.end() ? nullptr : it->second;
}

jlong registerSession(const std::shared_ptr<KpmSession> &session) {
    std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
    const jlong id = gKpmNextId++;
    gKpmSessions[id] = session;
    return id;
}

// Deep copy of a reference data set (malloc'd like artoolkitX's own, so kpmDeleteRefDataSet frees it).
KpmRefDataSet *copyRefDataSet(const KpmRefDataSet *src) {
    if (!src) return nullptr;
    auto *dst = static_cast<KpmRefDataSet *>(calloc(1, sizeof(KpmRefDataSet)));
    if (!dst) return nullptr;
    if (src->num > 0 && src->refPoint) {
        dst->refPoint = static_cast<KpmRefData *>(malloc(sizeof(KpmRefData) * src->num));
        if (!dst->refPoint) {
            free(dst);
            return nullptr;
        }
        memcpy(dst->refPoint, src->refPoint, sizeof(KpmRefData) * src->num);
        dst->num = src->num;
    }
    if (src->pageNum > 0 && src->pageInfo) {
        dst->pageInfo = static_cast<KpmPageInfo *>(calloc(src->pageNum, sizeof(KpmPageInfo)));
        if (!dst->pageInfo) {
            kpmDeleteRefDataSet(&dst);
            return nullptr;
        }
        dst->pageNum = src->pageNum;
        for (int i = 0; i < src->pageNum; ++i) {
            const KpmPageInfo &from = src->pageInfo[i];
            KpmPageInfo &to = dst->pageInfo[i];
            to.pageNo = from.pageNo;
            to.imageNum = 0;
            to.imageInfo = nullptr;
            if (from.imageNum > 0 && from.imageInfo) {
                to.imageInfo = static_cast<KpmImageInfo *>(malloc(sizeof(KpmImageInfo) * from.imageNum));
                if (!to.imageInfo) {
                    kpmDeleteRefDataSet(&dst);
                    return nullptr;
                }
                memcpy(to.imageInfo, from.imageInfo, sizeof(KpmImageInfo) * from.imageNum);
                to.imageNum = from.imageNum;
            }
        }
    }
    return dst;
}

bool directBuffer(JNIEnv *env, jobject buffer, jlong requiredBytes, ARUint8 **out) {
    if (!buffer || !out || requiredBytes <= 0) return false;
    void *ptr = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!ptr || capacity < requiredBytes) return false;
    *out = static_cast<ARUint8 *>(ptr);
    return true;
}

ARParamLT *makeCameraParams(
        int width,
        int height,
        float fx,
        float fy,
        float cx,
        float cy) {
    if (width <= 0 || height <= 0 || fx <= 0.0f || fy <= 0.0f) return nullptr;

    ARParam param{};
    if (arParamClear(&param, width, height, AR_DIST_FUNCTION_VERSION_DEFAULT) < 0) {
        return nullptr;
    }

    // Camera matrix K in artoolkitX's 3x4 form.
    param.mat[0][0] = static_cast<ARdouble>(fx);
    param.mat[0][1] = 0.0;
    param.mat[0][2] = static_cast<ARdouble>(cx);
    param.mat[0][3] = 0.0;
    param.mat[1][0] = 0.0;
    param.mat[1][1] = static_cast<ARdouble>(fy);
    param.mat[1][2] = static_cast<ARdouble>(cy);
    param.mat[1][3] = 0.0;
    param.mat[2][0] = 0.0;
    param.mat[2][1] = 0.0;
    param.mat[2][2] = 1.0;
    param.mat[2][3] = 0.0;

    // Version-5 distortion layout is OpenCV's 12 coefficients followed by fx/fy/cx/cy/scale.
    // Coefficients remain zero from arParamClear(), so this is an identity distortion model in the
    // correct pixel calibration. Camera2 distortion can be plumbed here later without changing KPM.
    param.dist_factor[12] = static_cast<ARdouble>(fx);
    param.dist_factor[13] = static_cast<ARdouble>(fy);
    param.dist_factor[14] = static_cast<ARdouble>(cx);
    param.dist_factor[15] = static_cast<ARdouble>(cy);
    param.dist_factor[16] = 1.0;

    return arParamLTCreate(&param, AR_PARAM_LT_DEFAULT_OFFSET);
}


// Keep at most maxFeatures of a freshly generated single-page set. Upstream kpmGenRefDataSet honours
// maxFeatureNum only on the SURF path (the FREAK path keeps every detected point), and the FREAK
// points carry no detector response in KpmRefData, so the cap keeps a spatially even subset: points
// are bucketed on an 8x8 grid over the page image and taken round-robin across buckets (in detector
// order within a bucket), then kept in their original order. Returns the number kept.
int capFeatures(KpmRefDataSet *set, int maxFeatures) {
    if (!set || maxFeatures <= 0 || set->num <= maxFeatures || !set->refPoint) return set ? set->num : 0;
    constexpr int kGrid = 8;
    float width = 1.0f;
    float height = 1.0f;
    if (set->pageNum > 0 && set->pageInfo && set->pageInfo[0].imageNum > 0 && set->pageInfo[0].imageInfo) {
        width = std::max(1, set->pageInfo[0].imageInfo[0].width);
        height = std::max(1, set->pageInfo[0].imageInfo[0].height);
    }
    std::vector<std::vector<int>> buckets(kGrid * kGrid);
    for (int i = 0; i < set->num; ++i) {
        const int bx = std::min(kGrid - 1, std::max(0, static_cast<int>(set->refPoint[i].coord2D.x / width * kGrid)));
        const int by = std::min(kGrid - 1, std::max(0, static_cast<int>(set->refPoint[i].coord2D.y / height * kGrid)));
        buckets[by * kGrid + bx].push_back(i);
    }
    std::vector<int> keep;
    keep.reserve(maxFeatures);
    for (size_t round = 0; static_cast<int>(keep.size()) < maxFeatures; ++round) {
        bool any = false;
        for (const auto &bucket : buckets) {
            if (round < bucket.size()) {
                any = true;
                keep.push_back(bucket[round]);
                if (static_cast<int>(keep.size()) == maxFeatures) break;
            }
        }
        if (!any) break;
    }
    std::sort(keep.begin(), keep.end());
    for (size_t k = 0; k < keep.size(); ++k) set->refPoint[k] = set->refPoint[keep[k]];
    set->num = static_cast<int>(keep.size());
    return set->num;
}

void deletePageHandles(KpmSession *session) {
    for (auto &entry : session->pageHandles) {
        if (entry.second) kpmDeleteHandle(&entry.second);
    }
    session->pageHandles.clear();
}

void refreshSkipPages(KpmSession *session) {
    session->skipPages.clear();
    if (!session->atlas) return;
    for (int i = 1; i < session->atlas->pageNum; ++i) {
        session->skipPages.push_back(session->atlas->pageInfo[i].pageNo);
    }
}

// In the BINARY_FEATURE (FREAK) path kpmMatching() matches the query against every page at once and
// then re-runs the identical ICP pose solve for the matched image once per page slot; kpmGetPose()
// returns the first slot. Skipping every slot but the first yields the same pose, page and error with
// N-1 fewer pose solves. kpmMatching clears the skip flags itself, so they are set before every call.
void skipRedundantPoseSolves(KpmHandle *handle, std::vector<int> &skipPages) {
#if BINARY_FEATURE
    if (!skipPages.empty()) {
        kpmSetMatchingSkipPage(handle, skipPages.data(), static_cast<int>(skipPages.size()));
    }
#else
    (void) handle;
    (void) skipPages;
#endif
}

// A matcher holding only [pageNo]'s features, built from the session atlas (no image processing).
KpmHandle *buildPageHandle(KpmSession *session, int pageNo) {
    if (!session->atlas || !session->cameraParams) return nullptr;
    const KpmRefDataSet *atlas = session->atlas;
    int pageIndex = -1;
    for (int i = 0; i < atlas->pageNum; ++i) {
        if (atlas->pageInfo[i].pageNo == pageNo) {
            pageIndex = i;
            break;
        }
    }
    if (pageIndex < 0) return nullptr;
    int count = 0;
    for (int i = 0; i < atlas->num; ++i) {
        if (atlas->refPoint[i].pageNo == pageNo) ++count;
    }
    if (count == 0) return nullptr;

    KpmRefDataSet subset{};
    subset.refPoint = static_cast<KpmRefData *>(std::malloc(sizeof(KpmRefData) * count));
    if (!subset.refPoint) return nullptr;
    int k = 0;
    for (int i = 0; i < atlas->num; ++i) {
        if (atlas->refPoint[i].pageNo == pageNo) subset.refPoint[k++] = atlas->refPoint[i];
    }
    subset.num = count;
    // kpmSetRefDataSet deep-copies pageInfo/imageInfo, so pointing at the atlas entry is safe.
    subset.pageInfo = &session->atlas->pageInfo[pageIndex];
    subset.pageNum = 1;

    KpmHandle *handle = kpmCreateHandle(session->cameraParams);
    if (handle && kpmSetRefDataSet(handle, &subset) < 0) kpmDeleteHandle(&handle);
    std::free(subset.refPoint);
    return handle;
}

// Runs one kpmMatching on [handle] and writes the 14-float pose block (+ correspondence count at
// out[14] when outLength > 14) and up to corrCapacity rows of (queryX, queryY, pageXmm, pageYmm).
// Returns the matched pageNo or -1. Never lets a C++ exception escape.
int matchWithHandle(
        KpmHandle *handle,
        std::vector<int> &skipPages,
        ARUint8 *luma,
        jfloat *out,
        int outLength,
        jfloat *corr,
        int corrCapacity) {
    try {
        skipRedundantPoseSolves(handle, skipPages);
        if (kpmMatching(handle, luma) < 0) return -1;

        float pose[3][4] = {};
        int pageNo = -1;
        float error = 0.0f;
        if (kpmGetPose(handle, pose, &pageNo, &error) < 0 || pageNo < 0) return -1;

        int inliers = 0;
        KpmResult *results = nullptr;
        int resultCount = 0;
        if (kpmGetResult(handle, &results, &resultCount) == 0 && results) {
            for (int i = 0; i < resultCount; ++i) {
                if (results[i].camPoseF == 0 && results[i].pageNo == pageNo) {
                    inliers = results[i].inlierNum;
                    break;
                }
            }
        }

        for (int r = 0; r < 3; ++r) {
            for (int c = 0; c < 4; ++c) out[r * 4 + c] = pose[r][c];
        }
        out[12] = error;
        out[13] = static_cast<float>(inliers);

        int written = 0;
#if BINARY_FEATURE
        if (corr && corrCapacity > 0 && handle->freakMatcher) {
            const int matchedId = handle->freakMatcher->matchedId();
            if (matchedId >= 0) {
                const vision::matches_t &matches = handle->freakMatcher->inliers();
                const auto &query = handle->freakMatcher->getQueryFeaturePoints();
                const auto &ref3d = handle->freakMatcher->get3DFeaturePoints(matchedId);
                for (const auto &m : matches) {
                    if (written >= corrCapacity) break;
                    if (m.ins < 0 || m.ref < 0 ||
                        m.ins >= static_cast<int>(query.size()) || m.ref >= static_cast<int>(ref3d.size())) {
                        continue;
                    }
                    jfloat *row = corr + written * 4;
                    row[0] = query[m.ins].x;
                    row[1] = query[m.ins].y;
                    row[2] = ref3d[m.ref].x;
                    row[3] = ref3d[m.ref].y;
                    ++written;
                }
            }
        }
#else
        (void) corr;
        (void) corrCapacity;
#endif
        if (outLength > 14) out[14] = static_cast<float>(written);
        return pageNo;
    } catch (...) {
        LOGE("matchWithHandle: native KPM exception");
        return -1;
    }
}

} // namespace
#endif

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeKpmAvailable(JNIEnv *, jobject) {
#ifdef HAVE_ARX_KPM
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeCreateCalibratedSession(
        JNIEnv *,
        jobject,
        jint width,
        jint height,
        jfloat fx,
        jfloat fy,
        jfloat cx,
        jfloat cy) {
#ifdef HAVE_ARX_KPM
    if (width <= 0 || height <= 0 || fx <= 0.0f || fy <= 0.0f) return 0;
    quietArtoolkitLogs();
    try {
    auto session = std::make_shared<KpmSession>();
    session->cameraParams = makeCameraParams(
        static_cast<int>(width),
        static_cast<int>(height),
        fx,
        fy,
        cx,
        cy
    );
    if (!session->cameraParams) {
        LOGE("nativeCreateCalibratedSession: camera parameter creation failed");
        return 0;
    }

    session->handle = kpmCreateHandle(session->cameraParams);
    if (!session->handle) {
        LOGE("nativeCreateCalibratedSession: KPM handle creation failed");
        return 0;
    }

    session->frameWidth = static_cast<int>(width);
    session->frameHeight = static_cast<int>(height);
    return registerSession(session);
    } catch (...) {
        LOGE("nativeCreateCalibratedSession: native exception");
        return 0;
    }
#else
    (void) width;
    (void) height;
    (void) fx;
    (void) fy;
    (void) cx;
    (void) cy;
    return 0;
#endif
}

// A new, independent session with the source's calibration and a deep copy of its atlas. The copy
// takes the source's work lock only for the memcpy; the clone's own (re)index happens on its first
// nativeAddPlanarPage, on whatever thread calls it, without touching the source. Until then the
// clone matches nothing. Returns 0 when the source is unknown or the copy fails.
JNIEXPORT jlong JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeCloneSession(
        JNIEnv *, jobject, jlong sourceValue) {
#ifdef HAVE_ARX_KPM
    quietArtoolkitLogs();
    try {
    std::shared_ptr<KpmSession> source = findSession(sourceValue);
    if (!source) return 0;
    auto clone = std::make_shared<KpmSession>();
    {
        std::lock_guard<std::mutex> workLock(source->work);
        if (!source->cameraParams) return 0;
        clone->cameraParams = arParamLTCreate(&source->cameraParams->param, AR_PARAM_LT_DEFAULT_OFFSET);
        if (!clone->cameraParams) {
            LOGE("nativeCloneSession: camera parameter copy failed");
            return 0;
        }
        if (source->atlas) {
            clone->atlas = copyRefDataSet(source->atlas);
            if (!clone->atlas) {
                LOGE("nativeCloneSession: atlas copy failed");
                return 0;
            }
        }
        clone->frameWidth = source->frameWidth;
        clone->frameHeight = source->frameHeight;
    }
    clone->handle = kpmCreateHandle(clone->cameraParams);
    if (!clone->handle) {
        LOGE("nativeCloneSession: KPM handle creation failed");
        return 0;
    }
    return registerSession(clone);
    } catch (...) {
        LOGE("nativeCloneSession: native exception");
        return 0;
    }
#else
    (void) sourceValue;
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeAddPlanarPage(
        JNIEnv *env,
        jobject,
        jlong sessionValue,
        jobject lumaBuffer,
        jint width,
        jint height,
        jfloat referenceDpi,
        jint pageNo,
        jint imageNo,
        jint maxFeatures) {
#ifdef HAVE_ARX_KPM
    // The shared_ptr keeps the session alive for the whole call even if it is destroyed concurrently.
    std::shared_ptr<KpmSession> session = findSession(sessionValue);
    if (!session) return -1;
    std::lock_guard<std::mutex> workLock(session->work);
    if (!session->handle || !session->cameraParams) return -1;
    if (width <= 0 || height <= 0 || referenceDpi <= 0.0f ||
        pageNo < 0 || imageNo < 0 || maxFeatures <= 0) {
        return -2;
    }

    const jlong requiredBytes = static_cast<jlong>(width) * static_cast<jlong>(height);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -3;

    KpmRefDataSet *page = nullptr;
    try {
        // Generate the page on its own first (kpmAddRefDataSet would merge it unconditionally), so a
        // failed or featureless page never enters the atlas (or the clones copied from it), and the
        // FREAK path's missing maxFeatures cap can be applied before merging.
        const int genResult = kpmGenRefDataSet(
            luma,
            static_cast<int>(width),
            static_cast<int>(height),
            referenceDpi,
            KpmProcFullSize,
            KpmCompNull,
            static_cast<int>(maxFeatures),
            static_cast<int>(pageNo),
            static_cast<int>(imageNo),
            &page
        );
        if (genResult < 0 || !page) {
            LOGE("nativeAddPlanarPage: kpmGenRefDataSet failed (%d)", genResult);
            if (page) kpmDeleteRefDataSet(&page);
            return -4;
        }
        const int generated = capFeatures(page, static_cast<int>(maxFeatures));
        if (generated <= 0) {
            LOGE("nativeAddPlanarPage: no features generated");
            kpmDeleteRefDataSet(&page);
            return -5;
        }
        const int mergeResult = kpmMergeRefDataSet(&session->atlas, &page); // consumes `page`
        if (page) kpmDeleteRefDataSet(&page);
        if (mergeResult < 0 || !session->atlas) {
            LOGE("nativeAddPlanarPage: kpmMergeRefDataSet failed (%d)", mergeResult);
            return -4;
        }

        if (session->indexed) {
            // kpmSetRefDataSet appends every atlas page to the handle's FREAK database under ids
            // 0..n-1; on a handle that already holds pages that re-adds id 0 and the database throws
            // "ID already exists". Rebuild the matcher so the full atlas loads into an empty database.
            kpmDeleteHandle(&session->handle);
            session->indexed = false;
            session->handle = kpmCreateHandle(session->cameraParams);
            if (!session->handle) {
                LOGE("nativeAddPlanarPage: KPM handle re-creation failed");
                return -6;
            }
        }
        const int setResult = kpmSetRefDataSet(session->handle, session->atlas);
        if (setResult < 0) {
            LOGE("nativeAddPlanarPage: kpmSetRefDataSet failed (%d)", setResult);
            return -6;
        }
        session->indexed = true;
        refreshSkipPages(session.get());

        LOGI(
            "nativeAddPlanarPage: page=%d image=%d features=%d atlasFeatures=%d",
            pageNo,
            imageNo,
            generated,
            session->atlas->num
        );
        return generated;
    } catch (...) {
        // Never let a C++ exception unwind through JNI (that aborts the process).
        if (page) kpmDeleteRefDataSet(&page);
        LOGE("nativeAddPlanarPage: KPM threw");
        return -7;
    }
#else
    (void) env;
    (void) sessionValue;
    (void) lumaBuffer;
    (void) width;
    (void) height;
    (void) referenceDpi;
    (void) pageNo;
    (void) imageNo;
    (void) maxFeatures;
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeMatchPlanar(
        JNIEnv *env,
        jobject,
        jlong sessionValue,
        jobject lumaBuffer,
        jfloatArray outArray) {
#ifdef HAVE_ARX_KPM
    try {
    std::shared_ptr<KpmSession> session = findSession(sessionValue);
    if (!session || !outArray) return -1;
    std::lock_guard<std::mutex> workLock(session->work);
    if (!session->handle || !session->atlas || !session->indexed) return -1;
    if (env->GetArrayLength(outArray) < 14) return -1;

    const jlong requiredBytes =
        static_cast<jlong>(session->frameWidth) * static_cast<jlong>(session->frameHeight);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -1;

    jfloat out[14] = {};
    const int pageNo = matchWithHandle(session->handle, session->skipPages, luma, out, 14, nullptr, 0);
    if (pageNo < 0) return -1;
    env->SetFloatArrayRegion(outArray, 0, 14, out);
    return static_cast<jint>(pageNo);
    } catch (...) {
        LOGE("nativeMatchPlanar: native exception");
        return -1;
    }
#else
    (void) env;
    (void) sessionValue;
    (void) lumaBuffer;
    (void) outArray;
    return -1;
#endif
}

// Like nativeMatchPlanar, plus: pageNo >= 0 restricts matching to that one atlas page (a lazily built
// single-page matcher — the query's FREAK search and Hough/homography verification then run against
// one page instead of all of them), out[14] receives the number of inlier correspondences written to
// corrArray (rows of queryX, queryY in frame pixels, pageX, pageY in KPM page millimetres; may be
// null). Returns the matched pageNo or -1.
JNIEXPORT jint JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeMatchPlanarEx(
        JNIEnv *env,
        jobject,
        jlong sessionValue,
        jobject lumaBuffer,
        jint pageNo,
        jfloatArray outArray,
        jfloatArray corrArray) {
#ifdef HAVE_ARX_KPM
    try {
    std::shared_ptr<KpmSession> session = findSession(sessionValue);
    if (!session || !outArray) return -1;
    std::lock_guard<std::mutex> workLock(session->work);
    if (!session->handle || !session->atlas || !session->indexed) return -1;
    if (env->GetArrayLength(outArray) < 15) return -1;

    const jlong requiredBytes =
        static_cast<jlong>(session->frameWidth) * static_cast<jlong>(session->frameHeight);
    ARUint8 *luma = nullptr;
    if (!directBuffer(env, lumaBuffer, requiredBytes, &luma)) return -1;

    KpmHandle *handle = session->handle;
    std::vector<int> noSkip;
    std::vector<int> *skip = &session->skipPages;
    if (pageNo >= 0) {
        auto it = session->pageHandles.find(static_cast<int>(pageNo));
        if (it == session->pageHandles.end()) {
            KpmHandle *built = nullptr;
            try {
                built = buildPageHandle(session.get(), static_cast<int>(pageNo));
            } catch (...) {
                built = nullptr;
            }
            if (!built) return -1;
            try {
                it = session->pageHandles.emplace(static_cast<int>(pageNo), built).first;
            } catch (...) {
                kpmDeleteHandle(&built); // not owned by the map: free it here
                throw;
            }
        }
        handle = it->second;
        skip = &noSkip;
    }

    const int corrCapacity = corrArray ? env->GetArrayLength(corrArray) / 4 : 0;
    std::vector<jfloat> corr(static_cast<size_t>(corrCapacity) * 4);
    jfloat out[15] = {};
    const int matched = matchWithHandle(
        handle, *skip, luma, out, 15, corrCapacity > 0 ? corr.data() : nullptr, corrCapacity);
    if (matched < 0) return -1;
    env->SetFloatArrayRegion(outArray, 0, 15, out);
    const int written = static_cast<int>(out[14]);
    if (corrArray && written > 0) env->SetFloatArrayRegion(corrArray, 0, written * 4, corr.data());
    return static_cast<jint>(matched);
    } catch (...) {
        LOGE("nativeMatchPlanarEx: native exception");
        return -1;
    }
#else
    (void) env;
    (void) sessionValue;
    (void) lumaBuffer;
    (void) pageNo;
    (void) outArray;
    (void) corrArray;
    return -1;
#endif
}

// Replace the atlas with an empty one. KPM has no API to remove pages from a handle's reference set,
// so the handle is rebuilt from the session's retained calibration; old pages can never match again.
JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeClearPages(
        JNIEnv *, jobject, jlong sessionValue) {
#ifdef HAVE_ARX_KPM
    try {
    std::shared_ptr<KpmSession> session = findSession(sessionValue);
    if (!session) return JNI_FALSE;
    std::lock_guard<std::mutex> workLock(session->work);
    if (!session->cameraParams) return JNI_FALSE;
    deletePageHandles(session.get());
    session->skipPages.clear();
    if (session->handle) kpmDeleteHandle(&session->handle);
    if (session->atlas) kpmDeleteRefDataSet(&session->atlas);
    session->indexed = false;
    session->handle = kpmCreateHandle(session->cameraParams);
    if (!session->handle) {
        // nativeAddPlanarPage / nativeMatchPlanar treat a null handle as unusable (-1).
        LOGE("nativeClearPages: KPM handle re-creation failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
    } catch (...) {
        LOGE("nativeClearPages: native exception");
        return JNI_FALSE;
    }
#else
    (void) sessionValue;
    return JNI_FALSE;
#endif
}

JNIEXPORT void JNICALL
Java_com_hereliesaz_sphereslam_nativebridge_KpmBridge_nativeDestroySession(
        JNIEnv *, jobject, jlong sessionValue) {
#ifdef HAVE_ARX_KPM
    // Unregister atomically; an unknown or already-destroyed id finds nothing (double-free guard).
    // In-flight calls hold their own shared_ptr, so ~KpmSession runs after the last of them returns.
    try {
        std::shared_ptr<KpmSession> session;
        {
            std::lock_guard<std::mutex> registryLock(gKpmRegistryMutex);
            auto it = gKpmSessions.find(sessionValue);
            if (it == gKpmSessions.end()) return;
            session = std::move(it->second);
            gKpmSessions.erase(it);
        }
        // Dropping `session` here frees the native objects unless another thread is mid-call.
    } catch (...) {
        LOGE("nativeDestroySession: native exception");
    }
#else
    (void) sessionValue;
#endif
}

} // extern "C"
