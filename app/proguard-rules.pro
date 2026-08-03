# Preserve data models from obfuscation to keep Gson/Retrofit working
-keep class io.github.draumaz.artlock.data.model.** { *; }

# Keep Compose-related classes if necessary (usually handled by Compose compiler)
-keepclassmembers class **.R$* {
    public static <fields>;
}