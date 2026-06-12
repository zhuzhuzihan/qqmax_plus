package byd.cxkcxkckx.watchdog;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * Simple activity used for testing the watchdog. It contains buttons that deliberately
 * cause a crash or a UI hang so the CrashApplication and HangWatcher dialogs can be
 * verified.
 */
public class TestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);

        Button crashBtn = new Button(this);
        crashBtn.setText("Crash App");
        crashBtn.setOnClickListener(v -> {
            // Deliberately throw an unchecked exception
            throw new RuntimeException("Test crash triggered by user");
        });

        Button hangBtn = new Button(this);
        hangBtn.setText("Hang UI (5s)");
        hangBtn.setOnClickListener(v -> {
            // Block the UI thread for 5 seconds to simulate a freeze
            long end = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < end) {
                // busy wait
            }
        });

        root.addView(crashBtn);
        root.addView(hangBtn);
        setContentView(root);
    }
}
