package com.hereliesaz.sphereslam

/**
 * Marks public SphereSLAM API that is not yet covered by the 1.0 compatibility contract: its shape
 * and tuning may change in a minor release.
 */
@RequiresOptIn(
    message = "This SphereSLAM API is experimental and may change without a major version.",
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
annotation class ExperimentalSphereSlamApi
