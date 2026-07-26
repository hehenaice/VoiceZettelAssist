# Default ProGuard rules for release builds.
# OkHttp, Kotlinx Coroutines, Compose — all ship their own consumer rules.

# Keep EncryptedSharedPreferences reflection targets
-keep class androidx.security.crypto.** { *; }

# Keep OkHttp (it uses reflection internally for platform detection)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
