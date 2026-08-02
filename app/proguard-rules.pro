# --- kotlinx.serialization: keep the generated serializers for our DTOs only ---
# The library ships its own consumer rules; these narrow the keeps to dev.zipshare models.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations

-keepclassmembers @kotlinx.serialization.Serializable class dev.zipshare.** {
    static <1>$Companion Companion;
    *** Companion;
}
-keepclasseswithmembers class dev.zipshare.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.zipshare.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class dev.zipshare.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class dev.zipshare.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit relies on generic signatures of suspend / Response return types.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Tink (pulled in by security-crypto) references ErrorProne annotations that are compile-only.
-dontwarn com.google.errorprone.annotations.**

# Optional TLS providers OkHttp probes for at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
