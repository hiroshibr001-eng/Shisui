package com.bruno.frameoverlay;

import android.app.Application;

public class BrunoApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLogger.install(this);
    }
}
