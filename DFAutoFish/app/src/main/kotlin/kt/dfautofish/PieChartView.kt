package kt.dfautofish

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44000000")
        style = Paint.Style.FILL
    }
    private val piePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val rect = RectF()
    private var ratio: Float = 0f

    fun setRatio(r: Float) {
        ratio = r.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val pad = borderPaint.strokeWidth
        rect.set(pad, pad, size - pad, size - pad)
        canvas.drawArc(rect, 0f, 360f, true, bgPaint)
        canvas.drawArc(rect, -90f, 360f * ratio, true, piePaint)
        canvas.drawArc(rect, 0f, 360f, true, borderPaint)
    }
}