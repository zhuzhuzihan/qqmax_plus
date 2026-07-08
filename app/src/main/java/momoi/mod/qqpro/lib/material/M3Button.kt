package momoi.mod.qqpro.lib.material

import android.content.Context
import android.view.Gravity
import android.graphics.Typeface
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * A Material 3 Expressive button with **spring press-recoil** — the signature micro-interaction
 * that makes the UI feel alive. On press the button scales to 0.96 and on release springs back to
 * 1.0 with a slight overshoot (under-damped spring, not a linear tween). The background color is
 * animated smoothly via [TonalLiftDrawable].
 *
 * Variants mirror MD3:
 *  - [FILLED]            solid primary, on-primary label (high emphasis)
 *  - [TONAL]             primary container (medium emphasis)
 *  - [TEXT]              no container, accent label (low emphasis)
 *  - [OUTLINED]          outline stroke, accent label
 *  - [ERROR]             translucent error container, error label
 */
class M3Button(ctx: Context) : TextView(ctx) {

    enum class Variant { FILLED, TONAL, TEXT, OUTLINED, ERROR }

    private var pressSpring: SpringAnimator? = null
        private var releaseDelayMs = 100L  // small delay so the recoil feels intentional, not a glitch

    init {
        gravity = Gravity.CENTER
        isSingleLine = true
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        isClickable = true
        isFocusable = true
        // Spring press-recoil: 0.96 on down, spring back with gentle overshoot. Slower spring (low
        // stiffness + medium bouncy — same recipe as M3Switch thumb pop, which reads as lively but
        // never twitchy) and a 60ms delay before the release animation so the recoil feels like a
        // deliberate "lift", not an instant snap on every tap.
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    scaleX = 0.96f; scaleY = 0.96f
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    pressSpring?.cancel()
                    v.removeCallbacks(releaseRunnable)
                    v.postDelayed(releaseRunnable, releaseDelayMs)
                }
            }
            false // let click listener still fire
        }
        variant(Variant.FILLED)
    }

    private val releaseRunnable = Runnable {
        val v = this
        pressSpring = SpringAnimator(v)
            .stiffness(M3Motion.SpringStiffnessLow)        // slower settle — visible, expressive
            .dampingRatio(M3Motion.SpringDampingMediumBouncy) // one soft overshoot
            .startFrom(0.96f)
            .apply { animateTo(1f) { s -> v.scaleX = s; v.scaleY = s } }
    }

    fun variant(v: Variant): M3Button = apply {
        val (container, label) = when (v) {
            Variant.FILLED           -> M3.primary to M3.onPrimary
            Variant.TONAL            -> M3.primaryContainer to M3.onPrimaryContainer
            Variant.TEXT             -> 0 to M3.primary
            Variant.OUTLINED         -> 0 to M3.primary
            Variant.ERROR            -> ((M3.error and 0x00FFFFFF) or 0x33_000000) to M3.error
        }
        setTextColor(label)
        val base = when (v) {
            Variant.OUTLINED -> M3.outlined(M3.outline, M3.radiusPill)
            else -> M3.rounded(container, M3.radiusPill)
        }
        background = M3.ripple(base)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
