# Keep JavaScript bridge
-keepclassmembers class com.leevi.welt.MainActivity$AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
