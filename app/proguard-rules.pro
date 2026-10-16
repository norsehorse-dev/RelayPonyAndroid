# RelayPony release keep rules (R8), applied on top of proguard-android-optimize.txt.
# Modelled on AgePonyAndroid's rules.
#
# What each block is for:
#   1. Attributes: generic signatures, inner classes and annotations that kotlinx.serialization
#      and Kotlin need at runtime, plus line numbers so crash reports stay readable. The source
#      file name is replaced by a constant so no build path or file name leaks into the APK.
#   2. kotlinx.serialization: the library ships its own consumer rules; these are the standard
#      extra rules for our own @Serializable classes (manifest, received-file records, identity
#      backup). Received files and backups are persisted as JSON, so the classes, their
#      companions and generated serializers are kept whole.
#   3. Enums: settings store enum names (theme mode) and rememberSaveable puts enums in the
#      saved-state Bundle, so enum constant names must survive obfuscation across upgrades.
#   4. JNA and UniFFI: the word-code pairing core (RelayPonyPake) is reached through JNA, which
#      binds native functions and Structure fields by name via reflection. The generated UniFFI
#      bindings and JNA itself are kept whole. java.awt is referenced by JNA's desktop code paths
#      that Android never takes.
#   5. BouncyCastle (via agepony-core): only the lightweight API is used, no JCA provider lookup
#      by name, so nothing is kept and R8 may shrink it. Unused provider/LDAP classes reference
#      javax.naming, which Android does not have.
#   6. zxing, zxing-android-embedded, Compose, AndroidX, kotlinx.coroutines: ship consumer rules
#      or use no reflection, so no rules are needed here.
#
# Reproducibility: no -printmapping, -printseeds, -printusage or -dump to absolute paths (AGP
# writes the mapping under build/outputs/mapping/ on its own), and nothing here depends on time
# or environment, so the same sources and toolchain give the same APK.

# ---- 1. Attributes ----
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 2. kotlinx.serialization (our @Serializable classes) ----
-keep @kotlinx.serialization.Serializable class com.relaypony.** { *; }
-keep class com.relaypony.**$$serializer { *; }
-keepclassmembers class com.relaypony.** {
    *** Companion;
}
-keepclasseswithmembers class com.relaypony.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.relaypony.**
-keepclassmembers class com.relaypony.<1> {
    static com.relaypony.<1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class com.relaypony.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.relaypony.** {
    public static ** INSTANCE;
}
-keepclassmembers class com.relaypony.<1> {
    public static com.relaypony.<1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 3. Enums ----
-keepclassmembers enum com.relaypony.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembernames enum com.relaypony.** {
    <fields>;
}

# ---- 4. JNA and UniFFI ----
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**

# ---- 5. BouncyCastle ----
-dontwarn javax.naming.**
