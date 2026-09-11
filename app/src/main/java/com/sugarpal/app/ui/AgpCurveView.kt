package com.sugarpal.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sugarpal.app.R
import com.sugarpal.app.data.AgpCalculator
import kotlin.math.max
import kotlin.math.min

/**
 * AGP 24 小时分位曲线：目标范围底色（3.9 ~ 8.9）+ P25~P75 分位带 + P50 中位数折线。
 * 数据来源 AgpCalculator.build().curve（已按整点 ±30 分钟入箱并完成空洞插值）。
 */
class AgpCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var curve: List<AgpCalculator.CurvePoint> = emptyList()

    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    private val padLeft = dp(34f)
    private val padRight = dp(12f)
    private val padTop = dp(12f)
    private val padBottom = dp(24f)

    private val colorBorder = context.getColor(R.color.color_border)
    private val colorText = context.getColor(R.color.color_text)
    private val colorTitle = context.getColor(R.color.color_title)
    private val colorGreenLight = context.getColor(R.color.color_green_light)
    private val colorGreenDark = context.getColor(R.color.color_green_dark)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorText
        textSize = dp(9.5f)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = colorBorder
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(colorGreenLight, 70)
    }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(colorTitle, 80)
    }

    private val medianPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colorGreenDark
    }

    private val bandPath = Path()
    private val medianPath = Path()

    fun setData(list: List<AgpCalculator.CurvePoint>) {
        curve = list
        invalidate()
    }

    private fun yMin(): Double {
        if (curve.isEmpty()) return 2.0
        return min(3.0, curve.minOf { it.p25 } - 1.0)
    }

    private fun yMax(): Double {
        if (curve.isEmpty()) return 12.0
        return max(10.0, curve.maxOf { it.p75 } + 1.0)
    }

    private fun plotW(): Float = width - padLeft - padRight
    private fun plotH(): Float = height - padTop - padBottom

    private fun xOf(hour: Int): Float = padLeft + plotW() * hour / 23f

    private fun yOf(value: Double): Float {
        val lo = yMin()
        val hi = yMax()
        val ratio = ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        return (padTop + plotH() * (1 - ratio)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        drawAxes(canvas)

        if (curve.isEmpty()) {
            textPaint.color = colorText
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无数据", width / 2f, height / 2f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            return
        }

        // 目标范围底色 3.9 ~ 8.9
        canvas.drawRect(
            padLeft,
            yOf(AgpCalculator.TARGET_HIGH_MAX),
            width - padRight,
            yOf(AgpCalculator.TARGET_LOW),
            targetPaint
        )

        drawBand(canvas)
        drawMedian(canvas)
        drawHourLabels(canvas)
    }

    private fun drawAxes(canvas: Canvas) {
        val lo = yMin()
        val hi = yMax()
        val steps = 4
        textPaint.color = colorText
        textPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..steps) {
            val value = lo + (hi - lo) * i / steps
            val y = yOf(value)
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
            canvas.drawText(String.format("%.1f", value), padLeft - dp(4f), y + dp(3f), textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawBand(canvas: Canvas) {
        bandPath.reset()
        curve.forEachIndexed { i, p ->
            val x = xOf(p.hour)
            val y = yOf(p.p75)
            if (i == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
        }
        for (i in curve.indices.reversed()) {
            bandPath.lineTo(xOf(curve[i].hour), yOf(curve[i].p25))
        }
        bandPath.close()
        canvas.drawPath(bandPath, bandPaint)
    }

    private fun drawMedian(canvas: Canvas) {
        medianPath.reset()
        curve.forEachIndexed { i, p ->
            val x = xOf(p.hour)
            val y = yOf(p.p50)
            if (i == 0) medianPath.moveTo(x, y) else medianPath.lineTo(x, y)
        }
        canvas.drawPath(medianPath, medianPaint)
    }

    private fun drawHourLabels(canvas: Canvas) {
        textPaint.color = colorText
        textPaint.textAlign = Paint.Align.CENTER
        listOf(0, 6, 12, 18, 23).forEach { h ->
            val x = xOf(h).coerceIn(padLeft, width - padRight)
            canvas.drawText(String.format("%02d:00", h), x, height - dp(6f), textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
