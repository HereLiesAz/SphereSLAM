# Consumer ProGuard rules for SphereSLAM's :core:nativebridge.
#
# The KPM JNI layer resolves its bridge class and native methods by name, so a consuming app's
# R8/ProGuard must not rename or strip them. A library module's own `proguardFiles` only apply when
# the library itself is minified (it isn't here), so these ship as `consumerProguardFiles` to reach
# a minified consumer app's R8 run.

# Keep native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Static-name JNI entry points in KpmBridge.cpp depend on this exact class/method naming contract.
-keep class com.hereliesaz.sphereslam.nativebridge.KpmBridge { *; }
