package io.projectyt.spike

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToLong

internal class TrimTimeline(
    context: Context,
    private val durationUs: Long,
    initialRange: ClipRange,
    private val onChanged: (start: Boolean, timeUs: Long) -> Unit,
    private val onFocused: (start: Boolean, focused: Boolean) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inset = dp(24f)
    private var range = initialRange
    private var playheadUs = initialRange.startUs
    private var startSelected = true
    private var touchGesture = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    fun showSelection(selection: ClipRange) {
        range = selection
        updateDescription()
        invalidate()
    }

    fun showPlayhead(positionUs: Long) {
        if (playheadUs == positionUs) return
        playheadUs = positionUs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val middle = height / 2f
        val start = x(range.startUs)
        val end = x(range.endUs)
        rounded(canvas, RectF(inset, middle - dp(13f), width - inset, middle + dp(13f)), dp(6f), 0xFF344252.toInt())
        rounded(canvas, RectF(start, middle - dp(13f), end, middle + dp(13f)), dp(5f), 0xFF53CDAB.toInt())

        val playhead = x(playheadUs)
        paint.color = 0xFFF8FFFD.toInt()
        paint.strokeWidth = dp(2f)
        canvas.drawLine(playhead, middle - dp(20f), playhead, middle + dp(20f), paint)
        drawHandle(canvas, start, middle, startSelected)
        drawHandle(canvas, end, middle, !startSelected)
    }

    private fun drawHandle(canvas: Canvas, x: Float, middle: Float, selected: Boolean) {
        val active = selected && isFocused
        val handle = RectF(x - dp(6f), middle - dp(17f), x + dp(6f), middle + dp(17f))
        if (active) {
            val outline = RectF(handle).apply { inset(-dp(3f), -dp(3f)) }
            rounded(canvas, outline, dp(6f), Color.WHITE)
        }
        rounded(canvas, handle, dp(4f), if (active) 0xFF177C65.toInt() else 0xFFABF3DD.toInt())
        paint.color = if (active) Color.WHITE else 0xFF25574B.toInt()
        paint.strokeWidth = dp(1f)
        canvas.drawLine(x - dp(2f), middle - dp(6f), x - dp(2f), middle + dp(6f), paint)
        canvas.drawLine(x + dp(2f), middle - dp(6f), x + dp(2f), middle + dp(6f), paint)
    }

    private fun rounded(canvas: Canvas, bounds: RectF, radius: Float, color: Int) {
        paint.color = color
        canvas.drawRoundRect(bounds, radius, radius, paint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onFocused(startSelected, gainFocus)
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return super.onKeyDown(keyCode, event)
        }
        val seconds = when {
            event.repeatCount >= 18 -> 30L
            event.repeatCount >= 6 -> 10L
            else -> 1L
        }
        val position = if (startSelected) range.startUs else range.endUs
        val offset = seconds * 1_000_000 * if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        onChanged(startSelected, (position + offset).coerceIn(0, durationUs))
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!touchGesture) select(!startSelected)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchGesture = true
                parent.requestDisallowInterceptTouchEvent(true)
                requestFocus()
                select(abs(event.x - x(range.startUs)) <= abs(event.x - x(range.endUs)))
            }
            MotionEvent.ACTION_CANCEL -> {
                touchGesture = false
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val position = ((event.x - inset) / span()).coerceIn(0f, 1f) * durationUs
                onChanged(startSelected, position.roundToLong())
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    parent.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    touchGesture = false
                }
            }
            else -> return false
        }
        return true
    }

    private fun select(start: Boolean) {
        startSelected = start
        onFocused(start, hasFocus())
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = context.getString(
            R.string.timeline_description, trimTime(range.startUs), trimTime(range.endUs),
        )
    }

    private fun x(positionUs: Long) = inset + span() * (positionUs.toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat()
    private fun span() = (width - 2 * inset).coerceAtLeast(1f)
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
