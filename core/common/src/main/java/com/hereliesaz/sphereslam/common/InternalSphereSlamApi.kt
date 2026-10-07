package com.hereliesaz.sphereslam.common

/**
 * Marks cross-module implementation details that must remain bytecode-visible but are not part of
 * SphereSLAM's supported consumer API.
 *
 * Library modules opt in internally. Applications should not: these declarations may change or
 * disappear without compatibility shims, including before and after 1.0.
 */
@RequiresOptIn(
    message = "Internal SphereSLAM implementation API; do not depend on it from applications.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
annotation class InternalSphereSlamApi
