package momoi.mod.qqpro.hook

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.lib.dp
import byd.cxkcxkckx.watchdog.CrashHandler
import byd.cxkcxkckx.watchdog.HangWatcher
import byd.cxkcxkckx.watchdog.CrashApplication

/**
 * Hook into com.tencent.qqnt.watch.mainframe.MainActivity to:
 * 1. Start the watchdog system on app launch
 * 2. Check and display crash/hang reports from previous sessions
 */
@Mixin
class WatchdogActivityHook : com.tencent.qqnt.watch.mainframe.MainActivity() {
    companion object {
        private const val TAG = "WatchdogHook"
        private var watchdogStarted = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start watchdog system once
        if (!watchdogStarted) {
            watchdogStarted = true
            Utils.log("$TAG: Starting watchdog system")
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
            HangWatcher(this).start()
        }
        
        // Check for saved crash/hang report from previous session
        val sp = getSharedPreferences(CrashApplication.PREF_NAME, MODE_PRIVATE)
        val savedReport = sp.getString(CrashApplication.KEY_CRASH_REPORT, null)
        
        if (savedReport != null) {
            Utils.log("$TAG: Found saved report from previous crash/hang, showing dialog")
            val report = savedReport
            // Show modern dialog on the main thread with responsive layout
            runOnUiThread {
                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xF0_1F1F1F.toInt())
                    setPadding(16.dp, 16.dp, 16.dp, 16.dp)
                }
                
                // Title
                root.addView(TextView(this).apply {
                    text = "上次使用时崩溃了"
                    textSize = 18f
                    setTextColor(0xFF_FFFFFF.toInt())
                    setPadding(0, 0, 0, 8.dp)
                })
                
                // Message
                root.addView(TextView(this).apply {
                    text = "请把以下报告发送给开发者"
                    textSize = 13f
                    setTextColor(0xFF_BBBBBB.toInt())
                    setPadding(0, 0, 0, 12.dp)
                })
                
                // Report scroll with responsive height
                val scroll = ScrollView(this).apply {
                    isFillViewport = false
                }
                scroll.addView(TextView(this).apply {
                    text = report
                    textSize = 11f
                    setTextColor(0xFF_DDDDDD.toInt())
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    setBackgroundColor(0xFF_0D0D0D.toInt())
                })
                
                // Calculate responsive height based on screen size
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                val maxScrollHeight = (screenHeight * 0.4).toInt()
                val reportHeight = maxOf(100.dp, minOf(maxScrollHeight, 200.dp))
                
                root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, reportHeight))
                
                // First row: Copy and Share buttons
                val btnContainer1 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 12.dp, 0, 6.dp)
                }
                
                btnContainer1.addView(Button(this).apply {
                    text = "复制报告"
                    setTextColor(0xFF_FFFFFF.toInt())
                    setBackgroundColor(0xFF_2196F3.toInt())
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    setOnClickListener {
                        val cm = getSystemService(ClipboardManager::class.java)
                        val clip = ClipData.newPlainText("Crash Report", report)
                        cm.setPrimaryClip(clip)
                        Toast.makeText(this@WatchdogActivityHook, "报告已复制", Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = 4.dp
                })
                
                btnContainer1.addView(Button(this).apply {
                    text = "分享"
                    setTextColor(0xFF_FFFFFF.toInt())
                    setBackgroundColor(0xFF_4CAF50.toInt())
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    setOnClickListener {
                        val share = Intent(Intent.ACTION_SEND)
                        share.type = "text/plain"
                        share.putExtra(Intent.EXTRA_TEXT, report)
                        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(share)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = 4.dp
                })
                
                root.addView(btnContainer1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                
                // Create and show dialog with max width constraint (before adding exit button)
                val dialog = android.app.AlertDialog.Builder(this)
                        .setView(root)
                        .setCancelable(false)
                        .create()
                
                // Second row: Exit button (full width)
                root.addView(Button(this).apply {
                    text = "退出"
                    setTextColor(0xFF_FFFFFF.toInt())
                    setBackgroundColor(0xFF_F44336.toInt())
                    setPadding(8.dp, 8.dp, 8.dp, 8.dp)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 6.dp
                    }
                    setOnClickListener {
                        dialog.dismiss()
                    }
                })
                
                // Set responsive dialog dimensions
                dialog.window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF_1F1F1F.toInt()))
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val dialogWidth = minOf(screenWidth - 32.dp, 600.dp)
                    setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                }
                dialog.show()
            }
            
            // Clear the report after showing
            sp.edit().remove(CrashApplication.KEY_CRASH_REPORT).apply()
        }
    }
}