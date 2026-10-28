# Gson reflects over the iTunes response models, so keep their fields
-keep class io.github.draumaz.artlock.data.model.** { *; }

# Strip debug/verbose logging (track titles etc.) from release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
