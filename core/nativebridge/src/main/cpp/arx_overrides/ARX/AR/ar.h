// SphereSLAM override of artoolkitX's <ARX/AR/ar.h> (see CMakeLists.txt, arx_overrides): the upstream
// header unchanged, except that its allocation macros no longer call exit(1) when malloc fails. In C++
// they throw std::bad_alloc, which every SphereSLAM JNI entry point catches and turns into an error
// return; an exit() would have killed the host app. C sources (unused file loaders) keep upstream's.
#include_next <ARX/AR/ar.h>

#ifdef __cplusplus
#include <new>

#undef arMalloc
#define arMalloc(V,T,S)  \
{ if( ((V) = (T *)malloc( sizeof(T) * (S) )) == NULL ) \
{ARLOGe("Out of memory!!\n"); throw std::bad_alloc();} }

#undef arMallocClear
#define arMallocClear(V,T,S)  \
{ if( ((V) = (T *)calloc( (S), sizeof(T) )) == NULL ) \
{ARLOGe("Out of memory!!\n"); throw std::bad_alloc();} }
#endif
