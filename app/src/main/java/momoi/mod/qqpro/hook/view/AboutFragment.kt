package momoi.mod.qqpro.hook.view

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.hook.versionName
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.ota.OTAManager2
import momoi.mod.qqpro.util.Utils
import byd.cxkcxkckx.watchdog.TestFragment

private val ACCENT = 0xFF_4FC3F7.toInt()

/**
 * About dialog for QQ Max. A full-screen [MyDialogFragment] rather than the native TipsUtils
 * dialog, which renders with oversized margins on the round watch screen (the app forces a tiny
 * compile SDK and overrides display DPI — see [LinkOpenFragment]). Shows the app icon, version
 * and credits (scrollable) plus a 检查更新 button that force-checks via [OTAManager2]. A
 * left-to-right swipe dismisses it (for watches without a back button).
 */
class AboutFragment : MyDialogFragment() {
    // Counter for taps on the version name
    private var versionTapCount: Int = 0
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx)
            .vertical()
            .padding(left = 20.dp, top = 16.dp, right = 20.dp, bottom = 16.dp)
        root.setBackgroundColor(0xF0_121212.toInt())

        // Icon + version + credits scroll together so they never push the buttons off-screen.
        val scroll = ScrollView(ctx).apply { isFillViewport = false }
        val column = LinearLayout(ctx).vertical()
        column.gravity = Gravity.CENTER_HORIZONTAL
        column.content {
            val icon = add<ImageView>().apply {
                runCatching {
                    setImageDrawable(ctx.packageManager.getApplicationIcon(ctx.packageName))
                }.onFailure { Utils.log("AboutFragment: icon load failed: $it") }
            }
            (icon.layoutParams as LinearLayout.LayoutParams).apply {
                width = 56.dp
                height = 56.dp
                bottomMargin = 8.dp
            }

            add<TextView>()
                .text("QQ Max")
                .textSize(18f)
                .textColor(0xFF_FFFFFF)
                .gravity(Gravity.CENTER)
            add<TextView>()
                .text(versionName)
                .textSize(12f)
                .textColor(0xFF_BBBBBB)
                .gravity(Gravity.CENTER)
                .padding(bottom = 12.dp)
                .clickable {
                    // Increment tap counter
                    versionTapCount++
                    if (versionTapCount >= 5) {
                        versionTapCount = 0
                        val fragment = TestFragment()
                        // Show the test dialog
                        fragment.show(childFragmentManager, "testFragment")
                    }
                }

            add<TextView>()
                .text("NWear QQ · 爅峫\nQQ Pro · java30433\nQQ Max · AILIFE")
                .textSize(13f)
                .textColor(0xFF_DDDDDD)
                .gravity(Gravity.CENTER)
                .padding(bottom = 12.dp)

            button("检查更新", ACCENT, 0xFF_000000.toInt()) {
                OTAManager2(ctx).checkUpdate(true)
                dismiss()
            }
        }
        scroll.addView(column, ViewGroup.LayoutParams(FILL, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(FILL, FILL))

        return SwipeBackLayout(ctx).apply {
            addView(root, FILL, FILL)
            onSwipeBack = { dismiss() }
        }
    }

    // Ensure the dialog fills the screen for consistent appearance on all resolutions
    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun momoi.mod.qqpro.lib.LinearScope.button(
        label: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ) {
        add<TextView>()
            .text(label)
            .textSize(14f)
            .textColor(fg)
            .gravity(Gravity.CENTER)
            .width(FILL)
            .padding(top = 10.dp, bottom = 10.dp)
            .apply {
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = 22.dp.toFloat()
                }
            }
            .margin(top = 6.dp)
            .clickable(onClick)
    }
}
