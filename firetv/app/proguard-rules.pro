# Methods the YouTube preview page calls through the Reel JavaScript bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# Reflection-free app, but media3 loads some renderers by name
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-dontwarn com.google.zxing.**
