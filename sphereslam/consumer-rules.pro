# SphereSLAM consumer ProGuard rules.
# The native layer resolves these classes and their methods by name via JNI, so a consuming
# app's R8/ProGuard must not rename or strip them.
-keep class com.hereliesaz.sphereslam.** { *; }
-keep class com.hereliesaz.graffitixr.** { *; }
