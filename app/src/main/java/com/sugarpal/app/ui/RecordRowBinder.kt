package com.sugarpal.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.R
import com.sugarpal.app.data.MealDescriber
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt
import java.time.LocalDate
import java.time.LocalDateTime

/** 记录行渲染（主界面最近记录 / 日历当天记录共用），口径与 PC 端列表一致 */
object RecordRowBinder {

    fun bind(
        context: Context,
        dao: RecordDao,
        panelMealTimesProvider: () -> Map<String, LocalDateTime>,
        row: View,
        record: BloodSugarRecord,
        showDelete: Boolean,
        onDelete: ((BloodSugarRecord) -> Unit)?
    ) {
        val dot = row.findViewById<View>(R.id.dotView)
        val tvValue = row.findViewById<TextView>(R.id.tvValue)
        val tvTag = row.findViewById<TextView>(R.id.tvTag)
        val tvSub = row.findViewById<TextView>(R.id.tvSub)
        val tvTime = row.findViewById<TextView>(R.id.tvTime)
        val btnDelete = row.findViewById<TextView>(R.id.btnDelete)

        val period = record.mealPeriod?.takeIf { it.isNotBlank() } ?: "空腹"
        val normal = PeriodClassifier.isNormal(period, record.bloodSugar)
        val markColor = context.getColor(if (normal) R.color.color_normal else R.color.color_high)

        tvValue.text = String.format("%.1f", record.bloodSugar)
        tvTag.text = if (normal) "达标" else "超出范围"
        tvTag.setTextColor(markColor)
        dot.backgroundTintList = ColorStateList.valueOf(markColor)

        val bd: LocalDate? = PeriodClassifier.getBusinessDate(record.recordTime)
        val panel = if (bd != null && bd == PeriodClassifier.getBusinessDate(LocalDateTime.now())) {
            panelMealTimesProvider()
        } else {
            emptyMap()
        }
        val saved = if (bd != null) dao.findMealTimesByBusinessDate(bd) else LinkedHashMap()
        val history = if (bd != null) {
            dao.findLatestMealTimeBefore(PeriodClassifier.getBusinessDayStart(bd), record.recordTime)
        } else {
            null
        }
        val desc = MealDescriber.descriptionOf(record.recordTime, panel, saved, history)
        val type = record.mealType?.takeIf { it.isNotBlank() } ?: ""
        tvSub.text = listOf(period, desc, type)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")

        val note = record.note?.takeIf { it.isNotBlank() }
        tvTime.text = if (note == null) {
            TimeFmt.full(record.recordTime)
        } else {
            TimeFmt.full(record.recordTime) + " · " + note
        }

        btnDelete.visibility = if (showDelete) View.VISIBLE else View.GONE
        btnDelete.setOnClickListener { onDelete?.invoke(record) }
    }
}
