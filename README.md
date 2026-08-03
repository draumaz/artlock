# 🎨 Artlock

Artlock is a native Android application that transforms your device's lock screen and home screen into an immersive musical experience.

|            Disabled            |             Enabled             |
|:------------------------------:|:-------------------------------:|
| ![Home](img/off.jpg)           | ![Search](img/on.jpg)           |

---

### How it works:
1. **Detection:** It monitors your music notifications to identify the currently playing track (Spotify, Apple Music, etc.).
2. **Immersive Wallpaper:** Automatically applies the animated album cover (Canvas) or high-resolution static artwork as your wallpaper.
3. **Smart Fallback:** If a video isn't available, it fetches high-quality static art via the iTunes API.
4. **Customization:** Adjust dimming and blur levels in real-time to ensure your icons and widgets stay legible.

### Key Technologies:
* **Jetpack Compose & Material 3:** For a modern, fluid user interface.
* **AndroidX Media3 (ExoPlayer):** Powering smooth video wallpaper playback.
* **NotificationListenerService:** For real-time track synchronization.
