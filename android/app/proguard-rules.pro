# ProGuard/R8 — правила сохранения классов
# =========================================

# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**

# Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepattributes Signature
-keepattributes *Annotation*

# AndroidX Security / Keystore
-keep class androidx.security.** { *; }
-dontwarn androidx.security.**

# SQLCipher (если используется)
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Kotlin корутины (если используются)
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Приложение
-keep class com.instacapture.** { *; }
-dontwarn com.instacapture.**