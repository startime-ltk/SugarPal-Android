package com.sugarpal.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.bloodsugar.model.BloodSugarRecord
import com.sugarpal.app.R
import com.sugarpal.app.data.TimeFmt
import java.time.Duration

/**
 * 趋势曲线：单条连续曲线 + 逐点着色空心圆点 + 触摸显示时间/数值/正常区间，
 * 并绘制随餐别（时段）动态的上下限参考线（虚线）。口径与 PC 端一致。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var records: List<BloodSugarRecord> = emptyList()
    private var selectedIndex = -1
    var onPointSelected: ((BloodSugarRecord?) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val padLeft = dp(40f)
    private val padRight = dp(14f)
    private val padTop = dp(16f)
    private val padBottom = dp(28f)

    private val colorTitle = context.getColor(R.color.color_title)
    private val colorText = context.getColor(R.color.color_text)
    private val colorBorder = context.getColor(R.color.color_border)
    private val colorNormal = context.getColor(R.color.color_normal)
    private val colorHigh = context.getColor(R.color.color_high)
    private val colorPanel = context.getColor(R.color.color_panel)

    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorTitle
    }

    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }

    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPanel
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = colorBorder
    }

    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = dp(10f)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorTitle
    }

    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(11f)
    }

    private val path = Path()

    fun setData(list: List<BloodSugarRecord>) {
        records = list
        selectedIndex = -1
        onPointSelected?.invoke(null)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelection(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelection(x: Float, y: Float) {
        if (records.isEmpty()) return
        var best = -1
        var bestDist = Float.MAX_VALUE
        records.forEachIndexed { i, r ->
            val px = xOf(i, r)
            val py = yOf(r.bloodSugar)
            val dist = Math.hypot((x - px).toDouble(), (y - py).toDouble()).toFloat()
            if (dist < bestDist) {
                bestDist = dist
                best = i
            }
        }
        val picked = if (bestDist <= dp(52f)) best else -1
        if (picked != selectedIndex) {
            selectedIndex = picked
            onPointSelected?.invoke(if (picked >= 0) records[picked] else null)
            invalidate()
        }
    }

    // ---------------- 坐标换算 ----------------

    private val times: List<Long> get() = records.map { it.recordTime?.let { t -> t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() } ?: 0L }

    private fun minTime(): Long = times.minOrNull() ?: 0L
    private fun maxTime(): Long = times.maxOrNull() ?: 0L

    private fun yMin(): Double {
        val lo = (records.minOfOrNull { it.bloodSugar } ?: 3.9) - 1.0
        return minOf(3.0, lo)
    }

    private fun yMax(): Double {
        val hi = (records.maxOfOrNull { it.bloodSugar } ?: 7.8) + 1.0
        return maxOf(10.0, hi)
    }

    private fun plotW(): Float = width - padLeft - padRight
    private fun plotH(): Float = height - padTop - padBottom

    private fun xOf(index: Int, r: BloodSugarRecord): Float {
        if (records.size == 1) return padLeft + plotW() / 2f
        val span = (maxTime() - minTime()).toDouble()
        if (span <= 0.0) {
            return padLeft + plotW() * index / (records.size - 1).toFloat()
        }
        val t = times[index]
        return padLeft + (plotW() * ((t - minTime()) / span)).toFloat()
    }

    private fun yOf(value: Double): Float {
        val lo = yMin()
        val hi = yMax()
        val ratio = ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return (padTop + plotH() * (1 - ratio)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        drawGrid(canvas)
        if (records.isEmpty()) {
            textPaint.color = colorText
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无数据", width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        drawReferenceLines(canvas)
        drawCurve(canvas)
        drawDots(canvas)
        drawBubble(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val lo = yMin()
        val hi = yMax()
        val steps = 4
        gridPaint.color = colorBorder
        textPaint.color = colorText
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..steps) {
            val value = lo + (hi - lo) * i / steps
            val y = yOf(value)
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
            canvas.drawText(String.format("%.1f", value), padLeft - dp(4f), y + dp(3.5f), textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** 相邻两点之间按时段画出该段的正常上下限参考线 */
    private fun drawReferenceLines(canvas: Canvas) {
        for (i in 0 until records.size - 1) {
            val a = records[i]
            val b = records[i + 1]
            val range = com.bloodsugar.util.PeriodClassifier.getNormalRange(b.mealPeriod ?: "空腹")
            val x1 = xOf(i, a)
            val x2 = xOf(i + 1, b)
            refPaint.color = withAlpha(colorNormal, 150)
            canvas.drawLine(x1, yOf(range[1]), x2, yOf(range[1]), refPaint)
            refPaint.color = withAlpha(colorHigh, 130)
            canvas.drawLine(x1, yOf(range[0]), x2, yOf(range[0]), refPaint)
        }
        if (records.size == 1) {
            val r = records[0]
            val range = com.bloodsugar.util.PeriodClassifier.getNormalRange(r.mealPeriod ?: "空腹")
            val x = xOf(0, r)
            refPaint.color = withAlpha(colorNormal, 150)
            canvas.drawLine(padLeft, yOf(range[1]), width - padRight, yOf(range[1]), refPaint)
            refPaint.color = withAlpha(colorHigh, 130)
            canvas.drawLine(padLeft, yOf(range[0]), width - padRight, yOf(range[0]), refPaint)
        }
    }

    private fun drawCurve(canvas: Canvas) {
        if (records.size < 2) return
        path.reset()
        records.forEachIndexed { i, r ->
            val x = xOf(i, r)
            val y = yOf(r.bloodSugar)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, curvePaint)

        // 时间轴端点
        textPaint.color = colorText
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(TimeFmt.display(records.first().recordTime), padLeft, height - dp(8f), textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(TimeFmt.display(records.last().recordTime), width - padRight, height - dp(8f), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawDots(canvas: Canvas) {
        records.forEachIndexed { i, r ->
            val x = xOf(i, r)
            val y = yOf(r.bloodSugar)
            val normal = com.bloodsugar.util.PeriodClassifier.isNormal(r.mealPeriod ?: "空腹", r.bloodSugar)
            val radius = if (i == selectedIndex) dp(7f) else dp(5f)
            canvas.drawCircle(x, y, radius, dotFill)
            dotStroke.color = if (normal) colorNormal else colorHigh
            dotStroke.strokeWidth = if (i == selectedIndex) dp(3.2f) else dp(2.4f)
            canvas.drawCircle(x, y, radius, dotStroke)
        }
    }

    private fun drawBubble(canvas: Canvas) {
        if (selectedIndex < 0 || selectedIndex >= records.size) return
        val r = records[selectedIndex]
        val range = com.bloodsugar.util.PeriodClassifier.getNormalRange(r.mealPeriod ?: "空腹")
        val normal = com.bloodsugar.util.PeriodClassifier.isNormal(r.mealPeriod ?: "空腹", r.bloodSugar)
        val line1 = "${TimeFmt.full(r.recordTime)}"
        val line2 = String.format("%.1f mmol/L · %s · %s", r.bloodSugar, r.mealPeriod ?: "空腹", if (normal) "正常" else "偏高/偏低")
        val line3 = String.format("正常区间 %.1f ~ %.1f", range[0], range[1])

        val lines = listOf(line1, line2, line3)
        var textW = 0f
        lines.forEach { textW = maxOf(textW, bubbleText.measureText(it)) }
        val boxW = textW + dp(20f)
        val boxH = dp(58f)

        var left = xOf(selectedIndex, r) - boxW / 2f
        left = left.coerceIn(dp(4f), width - boxW - dp(4f))
        var top = yOf(r.bloodSugar) - boxH - dp(12f)
        if (top < dp(4f)) top = yOf(r.bloodSugar) + dp(12f)

        val rect = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), bubblePaint)
        lines.forEachIndexed { i, s ->
            canvas.drawText(s, left + dp(10f), top + dp(18f) + i * dp(15f), bubbleText)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
