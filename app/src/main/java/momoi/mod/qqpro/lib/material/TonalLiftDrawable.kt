package momoi.mod.qqpro.lib.material

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import momoi.mod.qqpro.lib.dp

/**
 * A drawable that smoothly animates between [restColor] and [liftColor] when [setLifted] is called.
 * Uses ARGB ValueAnimator with emphasized-decelerate easing for the Expressive "responsive lift" feel.
 */
private class TonalLiftDrawable(
    private val restColor: Int,
    private val liftColor: Int,
    private val cornerRadius: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = restColor }
    private var currentColor = restColor
    private var anim: ValueAnimator? = null

    fun setLifted(lifted: Boolean) {
        val target = if (lifted) liftColor else restColor
        if (currentColor == target) return
        anim?.cancel()
        anim = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, target).apply {
            duration = if (lifted) M3Motion.DurationShort3 else M3Motion.DurationShort2 // 150ms in, 100ms out
            interpolator = if (lifted) M3Motion.EasingEmphasizedDecel else M3Motion.EasingEmphasizedAccel
            addUpdateListener {
                currentColor = it.animatedValue as Int
                paint.color = currentColor
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            cornerRadius, cornerRadius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
