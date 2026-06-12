package byd.cxkcxkckx.watchdog;

import android.content.SharedPreferences;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * ANR-like watcher that detects main thread stalls (>5s) and persists a hang report
 * for viewing on the next app launch.
 */
public class HangWatcher {
    private static final long CHECK_INTERVAL_MS = 1000; // check every second
    private static final long THRESHOLD_MS = 5000; // consider hung if blocked >5s
    private static final String TAG = "HangWatcher";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Context context;
    private long lastTick;
    private volatile boolean triggered = false;

    public HangWatcher(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void start() {
        lastTick = System.currentTimeMillis();
        mainHandler.post(tickRunnable);
        new Thread(monitorRunnable, "HangWatcher").start();
        Log.d(TAG, "HangWatcher started");
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            lastTick = System.currentTimeMillis();
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            while (!triggered) {
                long now = System.currentTimeMillis();
                if (now - lastTick > THRESHOLD_MS) {
                    triggered = true;
                    onHangDetected(now - lastTick);
                    break;
                }
                try {
                    Thread.sleep(CHECK_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                }
            }
        }
    };

    private void onHangDetected(long durationMs) {
        Log.w(TAG, "Main thread hang detected: " + durationMs + " ms");
        final String report = String.format("应用卡死 %d ms\n线程堆栈:\n%s", durationMs, buildThreadDump());
        
        // Save report synchronously (will be shown on next launch)
        try {
            SharedPreferences sp = context.getSharedPreferences(CrashApplication.PREF_NAME, Context.MODE_PRIVATE);
            Log.d(TAG, "Saving hang report to SharedPreferences");
            sp.edit()
                    .putString(CrashApplication.KEY_CRASH_REPORT, report)
                    .commit();
            Log.d(TAG, "Hang report saved successfully");
        } catch (Exception ex) {
            Log.e(TAG, "Failed to save hang report", ex);
        }
        
        // No dialog is shown; report will appear on next launch
    }

    private String buildThreadDump() {
        StringBuilder sb = new StringBuilder();
        java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            sb.append("Thread: ").append(e.getKey().getName()).append("\n");
            for (StackTraceElement el : e.getValue()) {
                sb.append("  at ").append(el.toString()).append("\n");
            }
        }
        return sb.toString();
    }
}