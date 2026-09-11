package com.sugarpal.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sugarpal.app.R
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历：有记录的日期用粉色圆点标记，点击日期回调；支持月份切换与选中高亮。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * d

    var markedDates: Set<LocalDate> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    var selectedDate: LocalDate? = null
        set(value) {
            field = value
            invalidate()
        }

    var onDateSelected: ((LocalDate) -> Unit)? = null

    private var month: YearMonth = YearMonth.now()

    private val colorTitle = context.getColor(R.color.color_title)
    private val colorText = context.getColor(R.color.color_text)
    private val colorPink = context.getColor(R.color.color_pink)
    private val colorSelBg = context.getColor(R.color.color_title_light)
    private val colorPanel = context.getColor(R.color.color_panel)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
    }
    private val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        color = colorText
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPink
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorSelBg
    }

    private val weekLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")

    fun currentMonth(): YearMonth = month

    fun setMonth(ym: YearMonth) {
        month = ym
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val weekH = w / 7f * 0.7f
        val height = (weekH + (w / 7f) * 6f).toInt()
        setMeasuredDimension(w, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0) return

        val cellW = width / 7f
        val weekH = cellW * 0.7f

        // 星期表头
        weekLabels.forEachIndexed { i, label ->
            canvas.drawText(label, cellW * i + cellW / 2f, weekH * 0.75f, weekPaint)
        }

        val first = month.atDay(1)
        val firstDayOfWeek = first.dayOfWeek.value // 1=周一 ... 7=周日
        val leading = firstDayOfWeek - 1
        val daysInMonth = month.lengthOfMonth()
        val prevMonth = month.minusMonths(1)
        val daysInPrev = prevMonth.lengthOfMonth()

        val cellH = cellW
        var dayCounter = 1
        var nextMonthDay = 1
        val totalCells = 42

        for (cell in 0 until totalCells) {
            val row = cell / 7
            val col = cell % 7
            val cx = cellW * col + cellW / 2f
            val cy = weekH + cellH * row + cellH / 2f

            var dayNum: Int
            var date: LocalDate?
            var inMonth = true
            when {
                cell < leading -> {
                    dayNum = daysInPrev - (leading - 1 - cell)
                    date = prevMonth.atDay(dayNum)
                    inMonth = false
                }
                dayCounter <= daysInMonth -> {
                    dayNum = dayCounter
                    date = month.atDay(dayCounter)
                    dayCounter++
                }
                else -> {
                    dayNum = nextMonthDay
                    date = month.plusMonths(1).atDay(nextMonthDay)
                    nextMonthDay++
                    inMonth = false
                }
            }

            if (date == selectedDate) {
                canvas.drawCircle(cx, cy, cellH * 0.36f, selPaint)
            }

            textPaint.color = if (inMonth) colorText else withAlpha(colorText, 90)
            if (date == selectedDate) textPaint.color = colorPanel
            canvas.drawText(dayNum.toString(), cx, cy + dp(4.5f), textPaint)

            if (inMonth && markedDates.contains(date)) {
                canvas.drawCircle(cx, cy + cellH * 0.31f, dp(2.6f), dotPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val cellW = width / 7f
        val weekH = cellW * 0.7f
        if (event.y < weekH) return true
        val row = ((event.y - weekH) / cellW).toInt()
        val col = (event.x / cellW).toInt()
        if (row < 0 || row > 5 || col < 0 || col > 6) return true

        val leading = month.atDay(1).dayOfWeek.value - 1
        val idx = row * 7 + col
        val date: LocalDate? = when {
            idx < leading -> null
            idx - leading + 1 <= month.lengthOfMonth() -> month.atDay(idx - leading + 1)
            else -> null
        }
        if (date != null) {
            selectedDate = date
            onDateSelected?.invoke(date)
        }
        return true
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
}
