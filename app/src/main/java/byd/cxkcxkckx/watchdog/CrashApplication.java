package byd.cxkcxkckx.watchdog;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Application class that initializes watchdog (but actual startup is delegated to MainActivity hook).
 * CrashHandler and HangWatcher are instantiated and managed by WatchdogActivityHook.
 */
public class CrashApplication extends Application {
    public static final String PREF_NAME = "watchdog_prefs";
    public static final String KEY_CRASH_REPORT = "crash_report";
    private static final String TAG = "WatchdogApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CrashApplication onCreate - watchdog will be started from MainActivity hook");
    }
}