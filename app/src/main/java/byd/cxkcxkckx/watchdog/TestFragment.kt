package byd.cxkcxkckx.watchdog

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment

/**
 * Comprehensive test dialog for the watchdog system with modern design.
 * Provides buttons to trigger various crash and hang scenarios for testing.
 */
class TestFragment : DialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = ScrollView(ctx).apply {
            isFillViewport = false
        }
        
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xF0_1F1F1F.toInt())
        }
        
        // Title
        content.addView(TextView(ctx).apply {
            text = "Watchdog 测试"
            textSize = 20f
            setTextColor(0xFF_FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        })
        
        // Helper function to create section headers
        fun addSection(title: String) {
            content.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTextColor(0xFF_4FC3F7.toInt())
                setPadding(0, 16, 0, 8)
            })
        }
        
        // Helper function to create test buttons
        fun addTestButton(label: String, action: () -> Unit) {
            content.addView(Button(ctx).apply {
                text = label
                setTextColor(0xFF_FFFFFF.toInt())
                setBackgroundColor(0xFF_2196F3.toInt())
                setPadding(12, 12, 12, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }
                setOnClickListener {
                    try {
                        action()
                    } catch (e: Exception) {
                        throw e
                    }
                }
            })
        }
        
        // Crash Tests Section
        addSection(" 崩溃测试")
        
        addTestButton("NullPointerException") {
            val x: String? = null
            x!!.length
        }
        
        addTestButton("ArrayIndexOutOfBoundsException") {
            val arr = IntArray(5)
            arr[100].toString()
        }
        
        addTestButton("ArithmeticException") {
            (1 / 0).toString()
        }
        
        addTestButton("ClassCastException") {
            val obj: Any = "string"
            (obj as Int).toString()
        }
        
        addTestButton("RuntimeException") {
            throw RuntimeException("Test RuntimeException")
        }
        
        // Hang Tests Section
        addSection(" 卡死测试")
        
        addTestButton("卡死 5 秒") {
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end) {
                // busy wait
            }
        }
        
        addTestButton("卡死 10 秒") {
            val end = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < end) {
                // busy wait
            }
        }
        
        addTestButton("无限循环") {
            @Suppress("ControlFlowWithEmptyBody")
            while (true) {
                // infinite loop
            }
        }
        
        // Thread Tests Section
        addSection(" 线程测试")
        
        addTestButton("后台线程崩溃") {
            Thread {
                throw RuntimeException("Background thread crash")
            }.start()
        }
        
        addTestButton("延迟 2 秒后崩溃") {
            Thread {
                Thread.sleep(2000)
                throw RuntimeException("Delayed crash")
            }.start()
        }
        
        // Action Section
        addSection("✓ 操作")
        
        addTestButton("关闭测试界面") {
            dismiss()
        }
        
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}