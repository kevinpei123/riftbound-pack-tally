# ---------------------------------------------------------------------------
# Riftbound Pack Tally — R8 / ProGuard keep rules
#
# Release builds enable R8 code + resource shrinking (see build.gradle.kts).
# Most libraries ship their own consumer rules (Room, OkHttp, Retrofit, ML Kit,
# CameraX, Compose), so the rules below only cover what R8 cannot infer on its
# own: kotlinx.serialization model (de)serialization and the reflection-driven
# Retrofit service interfaces.
# ---------------------------------------------------------------------------

# Annotations + generic signatures are read at runtime by serialization and
# Retrofit; R8 strips these attributes by default.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, Signature, InnerClasses, EnclosingMethod, Exceptions

# ---------------------------------------------------------------------------
# kotlinx.serialization
# Canonical rules from the kotlinx.serialization README. Without these the JSON
# layer (JustTCG pricing, Riftcodex card DB, currency rates, backup export /
# import) fails at runtime once R8 is on.
# ---------------------------------------------------------------------------
-if @kotlinx.serialization.Serializable class **
-keepclassmembers public class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the synthetic serializer classes themselves.
-keepclassmembers class **$$serializer { *; }

# Belt-and-suspenders: keep every one of our own @Serializable models intact.
-keep @kotlinx.serialization.Serializable class com.riftbound.packtally.** { *; }

# ---------------------------------------------------------------------------
# Retrofit
# Retrofit ships consumer rules, but our API interfaces are private/nested and
# invoked through a dynamic proxy, so be explicit about keeping any interface
# whose methods carry @retrofit2.http.* annotations, plus the coroutine glue.
# ---------------------------------------------------------------------------
-keepclasseswithmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---------------------------------------------------------------------------
# OkHttp references optional TLS providers reflectively; silence the warnings
# for classes that are never present on Android so R8 doesn't fail the build.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
