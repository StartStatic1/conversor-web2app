package com.streamflix.app;

import android.app.Application;
import android.webkit.WebView;

public class StreamFlixApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Habilita debug remoto do WebView via chrome://inspect (só em builds debug)
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }
}
