# SphereSLAM consumer ProGuard rules.
#
# :sphereslam itself needs no keep rules: it uses no reflection, and native code never resolves its
# classes by name. The only JNI contract is the bridge class com.hereliesaz.sphereslam.nativebridge.
# KpmBridge and its native methods, which :core:nativebridge's consumer-rules.pro already keeps.
# Public API a consumer calls is retained by R8 through ordinary reachability, so no blanket
# `-keep class com.hereliesaz.sphereslam.** { *; }` is shipped (it would defeat shrinking/obfuscation
# of every SphereSLAM class in the consuming app).
