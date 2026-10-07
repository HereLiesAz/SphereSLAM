package com.hereliesaz.sphereslam.reloc

/**
 * Marks the OpenCV-backed relocalization/photosphere surface that is intentionally public for
 * advanced consumers but is not yet covered by SphereSLAM's compatibility contract.
 */
@RequiresOptIn(
    message = "SphereSLAM :reloc is experimental and may change before 1.0.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalSphereSlamRelocApi
