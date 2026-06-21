# Mantém o JS bridge se você adicionar @JavascriptInterface no futuro
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
