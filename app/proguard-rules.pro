# BuildConfig fields used in AboutSection
-keep class com.skiletro.wheelwitch.BuildConfig { *; }

# Keep Compose metadata (handled by Compose compiler plugin, but belt-and-suspenders)
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Tink references Error Prone annotations that are compile-time only.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
