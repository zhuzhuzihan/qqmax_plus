package byd.cxkcxkckx.watchdog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Handler for uncaught exceptions. Persists the stack trace for viewing on the next launch.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;
    private static final String TAG = "CrashHandler";

    public CrashHandler(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        final String stackTrace = Log.getStackTraceString(e);
        Log.e(TAG, "Uncaught exception: " + e.getClass().getSimpleName());
        
        // Store the report synchronously (will be shown on next launch)
        try {
            SharedPreferences sp = context.getSharedPreferences(CrashApplication.PREF_NAME, Context.MODE_PRIVATE);
            Log.d(TAG, "Saving crash report to SharedPreferences");
            sp.edit()
                    .putString(CrashApplication.KEY_CRASH_REPORT, stackTrace)
                    .commit();
            Log.d(TAG, "Crash report saved successfully");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to save crash report", ex);
        }
        
        // Terminate without showing a dialog
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }
}