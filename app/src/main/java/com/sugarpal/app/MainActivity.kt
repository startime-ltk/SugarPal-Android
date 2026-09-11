package com.sugarpal.app

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt
import com.sugarpal.app.ui.AddRecordDialog
import com.sugarpal.app.ui.CalendarDialog
import com.sugarpal.app.ui.RecordRowBinder
import com.sugarpal.app.ui.TrendDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MainActivity : AppCompatActivity() {

    private lateinit var dao: RecordDao
    private lateinit var recordContainer: LinearLayout
    private lateinit var tvRecordCount: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var tvMealBusinessDate: TextView
    private lateinit var tvToday: TextView

    private val mealNames = listOf("早餐", "午餐", "晚餐", "加餐")
    private val mealViews = LinkedHashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        dao = RecordDao(this)

        recordContainer = findViewById(R.id.recordContainer)
        tvRecordCount = findViewById(R.id.tvRecordCount)
        tvEmptyHint = findViewById(R.id.tvEmptyHint)
        tvMealBusinessDate = findViewById(R.id.tvMealBusinessDate)
        tvToday = findViewById(R.id.tvToday)

        mealViews["早餐"] = findViewById(R.id.tvMealBreakfast)
        mealViews["午餐"] = findViewById(R.id.tvMealLunch)
        mealViews["晚餐"] = findViewById(R.id.tvMealDinner)
        mealViews["加餐"] = findViewById(R.id.tvMealSnack)
        mealNames.forEach { name -> bindMealRow(mealViews[name]!!, name) }

        findViewById<View>(R.id.btnAddRecord).setOnClickListener {
            AddRecordDialog(this, dao, { todayPanelMealTimes() }, { refreshAll() }).show()
        }
        findViewById<View>(R.id.btnTrend).setOnClickListener {
            TrendDialog(this, dao.findAll()).show()
        }
        findViewById<View>(R.id.btnCalendar).setOnClickListener {
            CalendarDialog(this, dao, { todayPanelMealTimes() }, { refreshAll() }).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    /** 当前业务日（凌晨 4 点边界） */
    private fun currentBusinessDate(): LocalDate =
        PeriodClassifier.getBusinessDate(LocalDateTime.now()) ?: LocalDate.now()

    /** 今日面板用餐时间：读 meal_times 表当前业务日数据，跨过凌晨 4 点自然清零 */
    private fun todayPanelMealTimes(): Map<String, LocalDateTime> =
        dao.findMealTimesByBusinessDate(currentBusinessDate())

    private fun bindMealRow(tv: TextView, mealName: String) {
        tv.setOnClickListener {
            val bd = currentBusinessDate()
            val saved = dao.findMealTimesByBusinessDate(bd)[mealName]
            val base = saved ?: LocalDateTime.now()
            TimePickerDialog(
                this,
                { _, h, mi ->
                    dao.upsertMealTime(bd, mealName, LocalDateTime.of(bd, LocalTime.of(h, mi)))
                    refreshAll()
                    Toast.makeText(this, "$mealName 用餐时间已保存", Toast.LENGTH_SHORT).show()
                },
                base.hour, base.minute, true
            ).show()
        }
        tv.setOnLongClickListener {
            val bd = currentBusinessDate()
            if (dao.findMealTimesByBusinessDate(bd).containsKey(mealName)) {
                AlertDialog.Builder(this)
                    .setTitle("清除$mealName 用餐时间？")
                    .setPositiveButton("清除") { _, _ ->
                        dao.deleteMealTime(bd, mealName)
                        refreshAll()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
    }

    private fun refreshAll() {
        val bd = currentBusinessDate()
        tvToday.text = TimeFmt.formatDate(LocalDate.now())
        tvMealBusinessDate.text = "业务日 ${TimeFmt.formatDate(bd)}（凌晨 4 点切换）"

        val panel = dao.findMealTimesByBusinessDate(bd)
        mealNames.forEach { name ->
            val tv = mealViews[name] ?: return@forEach
            val t = panel[name]
            tv.text = if (t == null) "未设置" else TimeFmt.hm(t)
            tv.setTextColor(getColor(if (t == null) R.color.color_text else R.color.color_orange_dark))
        }

        refreshRecords()
    }

    private fun refreshRecords() {
        val list = dao.findLatest(8)
        recordContainer.removeAllViews()
        tvEmptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvRecordCount.text = "共 ${dao.findAll().size} 条 · 显示近 8 条"

        list.forEach { r ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_record, recordContainer, false)
            RecordRowBinder.bind(this, dao, { todayPanelMealTimes() }, row, r, true) { record ->
                AlertDialog.Builder(this)
                    .setTitle("删除这条记录？")
                    .setMessage("${TimeFmt.full(record.recordTime)} · ${String.format("%.1f", record.bloodSugar)} mmol/L")
                    .setPositiveButton("删除") { _, _ ->
                        dao.delete(record.id)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        refreshAll()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            recordContainer.addView(row)
        }
    }
}
