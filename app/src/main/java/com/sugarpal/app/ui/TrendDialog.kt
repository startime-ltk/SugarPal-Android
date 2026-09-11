package com.sugarpal.app.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.R
import com.sugarpal.app.data.TimeFmt

/** 趋势曲线对话框：单条连续曲线 + 逐点着色空心圆点 + 触摸查看详情 */
class TrendDialog(
    private val activity: Activity,
    allRecords: List<BloodSugarRecord>
) {

    private val records: List<BloodSugarRecord> =
        allRecords.sortedBy { TimeFmt.format(it.recordTime) }.takeLast(200)

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_trend, null)
        val chart = view.findViewById<TrendChartView>(R.id.trendChart)
        val tvInfo = view.findViewById<TextView>(R.id.tvTrendInfo)
        val tvPick = view.findViewById<TextView>(R.id.tvTrendPick)

        chart.setData(records)

        tvInfo.text = if (records.isEmpty()) {
            "暂无数据"
        } else {
            "共 ${records.size} 条 · ${TimeFmt.full(records.first().recordTime)} ~ ${TimeFmt.full(records.last().recordTime)}"
        }

        tvPick.text = if (records.isEmpty()) "" else "点选任一圆点查看该次测量详情"

        chart.onPointSelected = { r ->
            if (r == null) {
                tvPick.text = "点选任一圆点查看该次测量详情"
            } else {
                val period = r.mealPeriod?.takeIf { it.isNotBlank() } ?: "空腹"
                val range = PeriodClassifier.getNormalRange(period)
                val normal = PeriodClassifier.isNormal(period, r.bloodSugar)
                tvPick.text = String.format(
                    "%s · %.1f mmol/L · %s（正常 %.1f ~ %.1f）· %s",
                    TimeFmt.full(r.recordTime), r.bloodSugar, period,
                    range[0], range[1], if (normal) "达标" else "超出范围"
                )
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("趋势曲线")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }
}
