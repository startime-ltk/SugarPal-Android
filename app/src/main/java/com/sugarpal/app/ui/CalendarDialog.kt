package com.sugarpal.app.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.R
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** 日历对话框：查看某天数据 + 补填三餐血糖（点击日期后按需录入） */
class CalendarDialog(
    private val activity: Activity,
    private val dao: RecordDao,
    private val panelMealTimesProvider: () -> Map<String, LocalDateTime>,
    private val onChanged: () -> Unit
) {

    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_calendar, null)
        val calendarView = view.findViewById<MonthCalendarView>(R.id.calendarView)
        val tvMonthTitle = view.findViewById<TextView>(R.id.tvMonthTitle)
        val tvDayTitle = view.findViewById<TextView>(R.id.tvDayTitle)
        val dayContainer = view.findViewById<LinearLayout>(R.id.dayRecordContainer)

        lateinit var dialog: AlertDialog

        fun refreshDay() {
            tvDayTitle.text = "${TimeFmt.formatDate(selected)} 当天记录"
            dayContainer.removeAllViews()
            val list = dao.findByBusinessDate(selected)
            if (list.isEmpty()) {
                val tv = TextView(activity)
                tv.text = "当天暂无记录，可点下方「补填这天血糖」"
                tv.setTextColor(activity.getColor(R.color.color_text))
                tv.textSize = 13f
                tv.setPadding(0, 12, 0, 12)
                dayContainer.addView(tv)
            } else {
                list.forEach { r ->
                    val row = LayoutInflater.from(activity).inflate(R.layout.item_record, dayContainer, false)
                    RecordRowBinder.bind(activity, dao, panelMealTimesProvider, row, r, true) { record: BloodSugarRecord ->
                        dao.delete(record.id)
                        Toast.makeText(activity, "已删除", Toast.LENGTH_SHORT).show()
                        calendarView.setMonth(month)
                        calendarView.selectedDate = selected
                        calendarView.markedDates =
                            dao.findDistinctDates().mapNotNull { TimeFmt.parseDate(it) }.toSet()
                        tvMonthTitle.text = "${month.year} 年 ${month.monthValue} 月"
                        refreshDay()
                        onChanged()
                    }
                    dayContainer.addView(row)
                }
            }
        }

        fun refreshCalendar() {
            calendarView.setMonth(month)
            calendarView.selectedDate = selected
            calendarView.markedDates = dao.findDistinctDates().mapNotNull { TimeFmt.parseDate(it) }.toSet()
            tvMonthTitle.text = "${month.year} 年 ${month.monthValue} 月"
        }

        calendarView.onDateSelected = { d ->
            selected = d
            refreshDay()
        }

        view.findViewById<View>(R.id.btnPrevMonth).setOnClickListener {
            month = month.minusMonths(1)
            refreshCalendar()
        }
        view.findViewById<View>(R.id.btnNextMonth).setOnClickListener {
            month = month.plusMonths(1)
            refreshCalendar()
        }
        view.findViewById<View>(R.id.btnFillDay).setOnClickListener {
            AddRecordDialog(
                activity = activity,
                dao = dao,
                panelMealTimesProvider = panelMealTimesProvider,
                onSaved = {
                    refreshCalendar()
                    refreshDay()
                    onChanged()
                },
                presetDate = selected
            ).show()
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("日历")
            .setView(view)
            .setPositiveButton("关闭", null)
            .create()

        refreshCalendar()
        refreshDay()
        dialog.show()
    }
}
