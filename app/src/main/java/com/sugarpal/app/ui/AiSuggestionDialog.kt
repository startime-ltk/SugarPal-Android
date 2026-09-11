package com.sugarpal.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sugarpal.app.R
import com.sugarpal.app.data.AiConfig
import com.sugarpal.app.data.AiSuggestionService
import com.sugarpal.app.data.RecordDao

/**
 * AI 控糖建议对话框（对应规格 6.7 / 9 章）：
 * 模型下拉 + 生成 / 设置 API Key / 复制；失败时提供「重试」与「切换模型」。
 */
object AiSuggestionDialog {

    fun show(activity: Activity, dao: RecordDao, config: AiConfig) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }

        fun label(text: String, size: Float): TextView = TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(activity.getColor(R.color.color_text))
        }

        fun providerLabels(): List<String> = AiConfig.PROVIDERS.map { p ->
            "${p.display}（${if (config.hasApiKey(p.id)) "已配置" else "未配置"}）"
        }

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, providerLabels()).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(AiConfig.PROVIDERS.indexOfFirst { it.id == config.activeProviderId() }.coerceAtLeast(0))
        }

        val rowModel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowModel.addView(label("模型", 13f))
        rowModel.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val tvStatus = label("", 11.5f).apply { setPadding(0, dp(4), 0, 0) }

        val tvResult = label("", 13f).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.15f)
        }
        val scrollResult = ScrollView(activity).apply {
            addView(tvResult, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
            )
            setPadding(0, dp(6), 0, dp(6))
        }

        fun button(text: String, bgRes: Int, colorRes: Int): TextView = TextView(activity).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(colorRes))
            setBackgroundResource(bgRes)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }

        val btnGenerate = button("生成建议", R.drawable.bg_btn_primary, android.R.color.white)
        val btnSettings = button("设置 API Key", R.drawable.bg_input, R.color.color_title_dark)
        val btnCopy = button("复制", R.drawable.bg_input, R.color.color_title_dark)

        val rowButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        rowButtons.addView(btnGenerate, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f))
        rowButtons.addView(btnSettings, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply { marginStart = dp(8) })
        rowButtons.addView(btnCopy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f).apply { marginStart = dp(8) })

        content.addView(rowModel)
        content.addView(tvStatus)
        content.addView(scrollResult)
        content.addView(rowButtons)

        val handler = Handler(Looper.getMainLooper())

        fun setBusy(busy: Boolean) {
            btnGenerate.isEnabled = !busy
            btnGenerate.alpha = if (busy) 0.6f else 1f
            btnGenerate.text = if (busy) "生成中…" else "生成建议"
            btnSettings.isEnabled = !busy
        }

        fun currentProvider() = AiConfig.PROVIDERS[spinner.selectedItemPosition.coerceIn(0, AiConfig.PROVIDERS.size - 1)]

        fun refreshStatus() {
            val p = currentProvider()
            tvStatus.text = "当前模型：${p.display}　${if (config.hasApiKey(p.id)) "已配置 Key" else "未配置 Key"}"
        }

        // 局部函数不支持前向引用，用引用变量打通 generate ↔ showFailure
        var generateRef: () -> Unit = {}

        fun showFailure(msg: String) {
            AlertDialog.Builder(activity)
                .setTitle("生成失败")
                .setMessage(msg)
                .setPositiveButton("重试") { _, _ -> generateRef() }
                .setNeutralButton("切换模型") { _, _ ->
                    tvStatus.text = "请在上方下拉框切换到其他已配置 Key 的模型，再点「生成建议」。"
                }
                .setNegativeButton("关闭", null)
                .show()
        }

        fun generate() {
            val provider = currentProvider()
            // 记住当前选择，下次打开沿用（规格 6.7）
            config.setActiveProvider(provider.id)
            runCatching { config.save() }

            tvStatus.text = "正在请求 ${provider.display}，请稍候…"
            setBusy(true)
            Thread {
                val outcome = runCatching { AiSuggestionService.generate(config, dao.findAll()) }
                handler.post {
                    setBusy(false)
                    outcome.onSuccess { text ->
                        tvResult.text = text
                        tvStatus.text = "由 ${provider.display} 生成，仅供参考，不能替代医生诊断。"
                    }.onFailure { e ->
                        val msg = e.message ?: "生成失败，请稍后重试"
                        tvStatus.text = "生成失败：$msg"
                        showFailure(msg)
                    }
                }
            }.start()
        }

        generateRef = { generate() }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                refreshStatus()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        refreshStatus()

        btnGenerate.setOnClickListener { generate() }
        btnCopy.setOnClickListener {
            val text = tvResult.text?.toString().orEmpty()
            if (text.isBlank()) {
                Toast.makeText(activity, "还没有可复制的内容", Toast.LENGTH_SHORT).show()
            } else {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("AI 控糖建议", text))
                Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        btnSettings.setOnClickListener {
            AiSettingsDialog.show(activity, config) {
                // 保存后刷新下拉框中的 Key 状态
                val keep = spinner.selectedItemPosition
                spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, providerLabels()).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                spinner.setSelection(keep.coerceAtLeast(0))
                refreshStatus()
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("AI 控糖建议")
            .setView(content)
            .setNegativeButton("关闭", null)
            .show()
    }
}
