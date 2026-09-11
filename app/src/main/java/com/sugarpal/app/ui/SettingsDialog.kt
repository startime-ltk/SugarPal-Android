package com.sugarpal.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sugarpal.app.R

/**
 * 设置对话框：数据管理（导入 / 导出 / 模板 / 清空）与版本信息。
 * 导入导出走系统文件选择器（SAF），由 MainActivity 注册的 ActivityResultLauncher 承接实际读写。
 */
object SettingsDialog {

    fun show(
        activity: Activity,
        recordCount: Int,
        onImport: () -> Unit,
        onExport: () -> Unit,
        onTemplate: () -> Unit,
        onClear: () -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }

        fun addSection(title: String) {
            root.addView(TextView(activity).apply {
                text = title
                textSize = 12.5f
                gravity = Gravity.START
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(activity.getColor(R.color.color_title_dark))
                setPadding(0, dp(10), 0, dp(5))
            })
        }

        fun addItem(title: String, desc: String, onClick: () -> Unit) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                setOnClickListener { onClick() }
            }
            box.addView(TextView(activity).apply {
                text = title
                textSize = 14f
                setTextColor(activity.getColor(R.color.color_text))
            })
            if (desc.isNotBlank()) {
                box.addView(TextView(activity).apply {
                    text = desc
                    textSize = 11.5f
                    setTextColor(activity.getColor(R.color.color_title))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            root.addView(box, lp)
        }

        addSection("数据管理")
        addItem("导入血糖数据（CSV）", "从手机文件中选择 CSV/TXT，解析后可预览再入库", onImport)
        addItem("导出全部数据（CSV）", "导出当前 $recordCount 条记录，可在 PC 端糖伴中导入", onExport)
        addItem("下载导入模板", "生成含示例数据的 CSV 模板，UTF-8 编码可直接用 Excel 打开", onTemplate)

        addSection("其他")
        addItem("清空全部数据", "删除全部血糖记录与用餐时间，操作不可撤销", onClear)
        addItem("AI 多模型建议", "血糖建议功能开发中，将在后续版本开放") {
            Toast.makeText(activity, "AI 建议功能将在下个版本开放", Toast.LENGTH_SHORT).show()
        }

        root.addView(TextView(activity).apply {
            text = "糖伴 SugarPal Android v1.1.0"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(R.color.color_title))
            setPadding(0, dp(8), 0, dp(2))
        })

        AlertDialog.Builder(activity)
            .setTitle("设置")
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }
}
