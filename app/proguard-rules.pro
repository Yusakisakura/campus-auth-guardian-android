# ── Guardian Core UniFFI ─────────────────────────────────────────────────
-keep class uniffi.guardian_core_android.** { *; }
-keep class com.campusauth.ffi.** { *; }

# ── JNA (required by UniFFI at runtime) ─────────────────────────────────
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn com.sun.jna.**

# ── Kotlin ──────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.**

# ── Kotlin Coroutines ──────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── Android lifecycle ──────────────────────────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends android.app.Application { *; }

# ── Services & Receivers ───────────────────────────────────────────────
-keep class com.campusauth.service.GuardianService { *; }
-keep class com.campusauth.service.BootReceiver { *; }

# ── Data classes used for JSON parsing ─────────────────────────────────
-keepclassmembers class com.campusauth.ffi.GuardianBridge$* { *; }

# ── General Android ────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile