package com.ym.lite.comment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.min

class CommentWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(235, 235, 235)
    }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(18f)
        isFakeBoldText = true
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
    }

    private var comments: List<String> = emptyList()
    private var selectedIndex = 0
    var onSelectionChanged: ((Int, String) -> Unit)? = null

    fun setComments(items: List<String>, selected: Int = 0) {
        comments = items.filter { it.isNotBlank() }
        selectedIndex = if (comments.isEmpty()) 0 else selected.mod(comments.size)
        invalidate()
    }

    fun selectNext() {
        if (comments.isEmpty()) return
        selectedIndex = (selectedIndex + 1) % comments.size
        onSelectionChanged?.invoke(selectedIndex, comments[selectedIndex])
        invalidate()
    }

    fun selectedComment(): String = comments.getOrNull(selectedIndex).orEmpty()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = dp(330f).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val side = min(width, height)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(8f)
        val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val visibleCount = min(comments.size.coerceAtLeast(1), 12)
        val sweep = 360f / visibleCount
        for (i in 0 until visibleCount) {
            val realIndex = if (comments.isEmpty()) 0 else i % comments.size
            fillPaint.color = when {
                comments.isNotEmpty() && realIndex == selectedIndex % comments.size -> Color.rgb(0, 120, 108)
                i % 2 == 0 -> Color.rgb(32, 32, 32)
                else -> Color.rgb(52, 52, 52)
            }
            canvas.drawArc(bounds, -90f + i * sweep, sweep, true, fillPaint)
        }

        canvas.drawCircle(cx, cy, radius, linePaint)
        for (i in 0 until visibleCount) {
            val angle = Math.toRadians((-90.0 + i * sweep).toDouble())
            val x = cx + kotlin.math.cos(angle).toFloat() * radius
            val y = cy + kotlin.math.sin(angle).toFloat() * radius
            canvas.drawLine(cx, cy, x, y, linePaint)
        }

        for (i in 0 until visibleCount) {
            val angle = Math.toRadians((-90.0 + (i + 0.5) * sweep).toDouble())
            val textRadius = radius * 0.73f
            val x = cx + kotlin.math.cos(angle).toFloat() * textRadius
            val y = cy + kotlin.math.sin(angle).toFloat() * textRadius + numberPaint.textSize * 0.35f
            canvas.drawText((i + 1).toString(), x, y, numberPaint)
        }

        fillPaint.color = Color.rgb(12, 12, 12)
        canvas.drawCircle(cx, cy, radius * 0.45f, fillPaint)
        canvas.drawCircle(cx, cy, radius * 0.45f, linePaint)

        centerPaint.textSize = dp(29f)
        canvas.drawText("🎡", cx, cy - dp(22f), centerPaint)
        centerPaint.textSize = dp(14f)
        drawCenteredLines(canvas, selectedComment().ifBlank { "أضف تعليقات للدولاب" }, cx, cy + dp(8f), radius * 0.72f)
    }

    private fun drawCenteredLines(canvas: Canvas, text: String, cx: Float, startY: Float, maxWidth: Float) {
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (centerPaint.measureText(candidate) <= maxWidth || current.isBlank()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
            if (lines.size >= 2) break
        }
        if (current.isNotBlank() && lines.size < 3) lines += current
        lines.take(3).forEachIndexed { index, line ->
            canvas.drawText(line.take(28), cx, startY + index * dp(18f), centerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP || comments.isEmpty()) return true
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
        if (degrees < 0) degrees += 360.0
        val visibleCount = min(comments.size, 12)
        val index = ((degrees / (360.0 / visibleCount)).toInt()).coerceIn(0, visibleCount - 1)
        selectedIndex = index % comments.size
        onSelectionChanged?.invoke(selectedIndex, comments[selectedIndex])
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
