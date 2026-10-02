package kt.dfautofish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 黑色 → 亮红色 的可拖拽色段选择器。
 * 用户拖动两个把手选出 [minT, maxT] 子区间。
 */
class ColorRangeSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_START = 0xFF000000.toInt()   // 黑
        private const val COLOR_END   = 0xFFFF0000.toInt()   // 亮红
        private const val MIN_GAP     = 0.02f                // 两点最小间距
    }

    var minT: Float = 0.20f
        private set
    var maxT: Float = 0.80f
        private set

    var onRangeChanged: ((Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        style = Paint.Style.FILL
    }
    private val selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val handleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5722")
        style = Paint.Style.FILL
    }

    private val barRect = RectF()
    private val handleRadius = 11f * density
    private val barHeight = 22f * density
    private val barPad = 18f * density

    /** -1 无；0 左把手；1 右把手 */
    private var dragging = -1

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val barTop = (h - barHeight) / 2f
        barRect.set(barPad, barTop, w - barPad, barTop + barHeight)
        barPaint.shader = LinearGradient(
            barRect.left, 0f, barRect.right, 0f,
            COLOR_START, COLOR_END, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 渐变条本体
        canvas.drawRoundRect(barRect, barHeight / 2f, barHeight / 2f, barPaint)

        val xMin = tToX(minT)
        val xMax = tToX(maxT)

        // 2. 未选中部分盖半透明黑
        canvas.save()
        canvas.clipRect(barRect)
        if (xMin > barRect.left) {
            canvas.drawRect(barRect.left, barRect.top, xMin, barRect.bottom, overlayPaint)
        }
        if (xMax < barRect.right) {
            canvas.drawRect(xMax, barRect.top, barRect.right, barRect.bottom, overlayPaint)
        }
        canvas.restore()

        // 3. 选中区间黄框
        canvas.drawRect(xMin, barRect.top, xMax, barRect.bottom, selectionBorderPaint)

        // 4. 两个把手
        drawHandle(canvas, xMin)
        drawHandle(canvas, xMax)
    }

    private fun drawHandle(canvas: Canvas, cx: Float) {
        val cy = height / 2f
        canvas.drawCircle(cx, cy, handleRadius, handleFillPaint)
        canvas.drawCircle(cx, cy, handleRadius, handleBorderPaint)
        canvas.drawCircle(cx, cy, handleRadius * 0.35f, handleDotPaint)
    }

    private fun tToX(t: Float): Float = barRect.left + t * barRect.width()

    private fun xToT(x: Float): Float =
        ((x - barRect.left) / barRect.width()).coerceIn(0f, 1f)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dMin = abs(event.x - tToX(minT))
                val dMax = abs(event.x - tToX(maxT))
                dragging = if (dMin <= dMax) 0 else 1
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != -1) {
                    updateFromTouch(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != -1) {
                    dragging = -1
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float) {
        val t = xToT(x)
        when (dragging) {
            0 -> minT = t.coerceAtMost(maxT - MIN_GAP)
            1 -> maxT = t.coerceAtLeast(minT + MIN_GAP)
        }
        invalidate()
        onRangeChanged?.invoke(minT, maxT)
    }

    fun setRange(min: Float, max: Float) {
        var a = min.coerceIn(0f, 1f)
        var b = max.coerceIn(0f, 1f)
        if (a > b) { val t = a; a = b; b = t }
        minT = a
        maxT = b
        invalidate()
    }
}