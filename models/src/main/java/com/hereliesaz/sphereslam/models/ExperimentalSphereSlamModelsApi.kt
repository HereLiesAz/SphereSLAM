package com.hereliesaz.sphereslam.models

/**
 * Marks optional model helpers that are usable by applications but are not yet part of the
 * compatibility-guaranteed SphereSLAM API.
 */
@RequiresOptIn(
    message = "SphereSLAM :models helpers are experimental and may change before 1.0.",
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
annotation class ExperimentalSphereSlamModelsApi
