-keep class com.skiletro.wheelwitch.BuildConfig { *; }

# Preserve annotations; retain source locations for release stack traces.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Tink references Error Prone annotations that are compile-time only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
