# Keep JavaScript interface members if any are added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Keep model classes used for JSON parsing via reflection-free manual parsing.
-keep class de.davidgrieser.container.model.** { *; }
