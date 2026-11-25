# The JS bridge is invoked reflectively from WebView; keep the annotated surface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.ytcache.app.CacheBridge { *; }
