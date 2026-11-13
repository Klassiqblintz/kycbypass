# KYC Bypass - ProGuard / R8 rules

# Keep Xposed-related classes
-keep class com.hazbu.kycbypass.xposed.** { *; }

# Keep hook classes
-keep class com.hazbu.kycbypass.hooks.** { *; }

# Keep engine classes
-keep class com.hazbu.kycbypass.engine.** { *; }

# Keep capture classes
-keep class com.hazbu.kycbypass.capture.** { *; }

# Keep service classes
-keep class com.hazbu.kycbypass.service.** { *; }

# Keep main app class and companion object
-keep class com.hazbu.kycbypass.KycBypassApp { *; }
-keepclassmembers class com.hazbu.kycbypass.KycBypassApp {
    static *;
}

# Keep SettingsManager
-keep class com.hazbu.kycbypass.utils.SettingsManager { *; }

# Keep all public methods for XposedBridge reflection hooks
-keepclassmembers class * {
    public <methods>;
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.android.exoplayer2.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
