package com.sugarpal.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.R
import com.sugarpal.app.data.MealDescriber
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 记录录入对话框：血糖值 / 时间 / 餐别（自动识别 + 手动覆盖）/ 备注。
 * 时段（meal_period）始终由 PeriodClassifier 自动计算，保证统计口径与 PC 端一致。
 */
class AddRecordDialog(
    private val activity: Activity,
    private val dao: RecordDao,
    private val panelMealTimesProvider: () -> Map<String, LocalDateTime>,
    private val onSaved: () -> Unit,
    private val presetDate: LocalDate? = null
) {

    private val mealTypeOptions = arrayOf("自动识别", "空腹", "餐前", "餐后", "睡前")

    private var currentTime: LocalDateTime = initialTime()

    private fun initialTime(): LocalDateTime {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val d = presetDate ?: return now
        return if (d == LocalDate.now()) now else LocalDateTime.of(d, LocalTime.of(8, 0))
    }

    /** 当日（业务日口径）最近一次用餐时间，来自面板 + meal_times 表 + 历史记录 */
    private fun nearestMealTime(recordTime: LocalDateTime): LocalDateTime? {
        val bd = PeriodClassifier.getBusinessDate(recordTime) ?: return null
        val panel = panelMealTimesProvider()
            .filterValues { PeriodClassifier.getBusinessDate(it) == bd }
        val saved = dao.findMealTimesByBusinessDate(bd)
        val history = dao.findLatestMealTimeBefore(PeriodClassifier.getBusinessDayStart(bd), recordTime)
        return MealDescriber.resolveNearestMealTime(recordTime, panel, saved, history)?.time
    }

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_add_record, null)
        val etSugar = view.findViewById<EditText>(R.id.etSugar)
        val etNote = view.findViewById<EditText>(R.id.etNote)
        val tvDupHint = view.findViewById<TextView>(R.id.tvDupHint)
        val tvPickDate = view.findViewById<TextView>(R.id.tvPickDate)
        val tvPickTime = view.findViewById<TextView>(R.id.tvPickTime)
        val tvAutoPeriod = view.findViewById<TextView>(R.id.tvAutoPeriod)
        val spMealType = view.findViewById<Spinner>(R.id.spMealType)
        view.findViewById<View>(R.id.tvDupHint)

        spMealType.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            mealTypeOptions.toList()
        )

        lateinit var dialog: AlertDialog

        fun updateAutoPeriod() {
            val mealTime = nearestMealTime(currentTime)
            val period = PeriodClassifier.classify(currentTime, mealTime)
            val range = PeriodClassifier.getNormalRange(period)
            val mealDesc = MealDescriber.descriptionOf(
                currentTime, panelMealTimesProvider(), dao.findMealTimesByBusinessDate(
                    PeriodClassifier.getBusinessDate(currentTime) ?: LocalDate.now()
                ), mealTime
            )
            tvAutoPeriod.text = String.format(
                "自动时段：%s（正常 %.1f ~ %.1f mmol/L）· %s", period, range[0], range[1], mealDesc
            )
        }

        fun updateDupHint() {
            val value = etSugar.text.toString().trim().toDoubleOrNull()
            if (value != null && dao.countByTimeAndValue(currentTime, value) > 0) {
                tvDupHint.visibility = View.VISIBLE
                tvDupHint.text = "提示：该时间点已存在相同数值的记录"
            } else {
                tvDupHint.visibility = View.GONE
            }
        }

        fun refreshTimeViews() {
            tvPickDate.text = TimeFmt.formatDate(currentTime.toLocalDate())
            tvPickTime.text = String.format("%02d:%02d", currentTime.hour, currentTime.minute)
            updateAutoPeriod()
            updateDupHint()
        }

        tvPickDate.setOnClickListener {
            DatePickerDialog(
                activity,
                { _, y, m, d ->
                    currentTime = currentTime.withYear(y).withMonth(m + 1).withDayOfMonth(d)
                    refreshTimeViews()
                },
                currentTime.year, currentTime.monthValue - 1, currentTime.dayOfMonth
            ).show()
        }

        tvPickTime.setOnClickListener {
            TimePickerDialog(
                activity,
                { _, h, mi ->
                    currentTime = currentTime.withHour(h).withMinute(mi).withSecond(0).withNano(0)
                    refreshTimeViews()
                },
                currentTime.hour, currentTime.minute, true
            ).show()
        }

        etSugar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateDupHint()
        })

        refreshTimeViews()

        dialog = AlertDialog.Builder(activity)
            .setTitle("记录血糖")
            .setView(view)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = etSugar.text.toString().trim().toDoubleOrNull()
                if (value == null) {
                    Toast.makeText(activity, "请输入血糖值", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (value < 0.0 || value > 50.0) {
                    Toast.makeText(activity, "血糖值需在 0 ~ 50 mmol/L 之间", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (dao.existsByTime(currentTime)) {
                    Toast.makeText(activity, "该时间点已存在记录，请调整时间", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val bd = PeriodClassifier.getBusinessDate(currentTime) ?: LocalDate.now()
                val mealTime = nearestMealTime(currentTime)
                val period = PeriodClassifier.classify(currentTime, mealTime)
                val manualIndex = spMealType.selectedItemPosition
                val mealType = if (manualIndex <= 0) {
                    PeriodClassifier.classifyMealType(currentTime, mealTime)
                } else {
                    mealTypeOptions[manualIndex]
                }
                val note = etNote.text.toString().trim().ifBlank { null }

                val record = BloodSugarRecord(currentTime, value, mealTime, period, mealType, note)
                dao.insert(record)
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                onSaved()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
