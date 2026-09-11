package com.sugarpal.app.ui

import android.app.Activity
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.sugarpal.app.R
import com.sugarpal.app.data.AiConfig

/**
 * AI 模型设置对话框（规格 6.1~6.3 / 6.7）：
 * 每个 provider 的 baseUrl / model / apiKey 独立存储，切换模型不影响其他配置。
 * API Key 留空表示保持原值不变（避免误清空）。
 */
object AiSettingsDialog {

    fun show(activity: Activity, config: AiConfig, onSaved: () -> Unit = {}) {
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
            setPadding(0, dp(6), 0, dp(2))
        }

        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(
                activity, android.R.layout.simple_spinner_item,
                AiConfig.PROVIDERS.map { it.display }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(AiConfig.PROVIDERS.indexOfFirst { it.id == config.activeProviderId() }.coerceAtLeast(0))
        }

        val etBase = EditText(activity).apply {
            textSize = 13f
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val etModel = EditText(activity).apply {
            textSize = 13f
            setSingleLine(true)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val etKey = EditText(activity).apply {
            textSize = 13f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        content.addView(label("模型", 13f))
        content.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label("接口地址（完整 chat/completions 地址）", 13f))
        content.addView(etBase, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label("模型名称", 13f))
        content.addView(etModel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label("API Key", 13f))
        content.addView(etKey, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(
            TextView(activity).apply {
                text = "Key 仅保存在本机，落盘时混淆存储（与 PC 端 config.properties 双向兼容）。\n" +
                    "配置文件：${config.filePath()}"
                textSize = 10.5f
                setTextColor(activity.getColor(R.color.color_text))
                setPadding(0, dp(8), 0, 0)
            }
        )

        val scroll = ScrollView(activity).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun currentProvider() =
            AiConfig.PROVIDERS[spinner.selectedItemPosition.coerceIn(0, AiConfig.PROVIDERS.size - 1)]

        /** 切换模型时载入该项的独立配置 */
        fun loadProvider() {
            val p = currentProvider()
            etBase.setText(config.baseUrl(p.id))
            etModel.setText(config.model(p.id))
            etKey.setText("")
            etKey.hint = if (config.hasApiKey(p.id)) "已配置，留空则保持不变" else "未配置，请填写"
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                loadProvider()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        loadProvider()

        val dialog = AlertDialog.Builder(activity)
            .setTitle("AI 模型设置")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = currentProvider()
                config.setActiveProvider(p.id)
                config.setBaseUrl(p.id, etBase.text.toString())
                config.setModel(p.id, etModel.text.toString())
                val keyInput = etKey.text.toString().trim()
                if (keyInput.isNotEmpty()) config.setApiKey(p.id, keyInput)

                try {
                    config.save()
                    Toast.makeText(activity, "已保存 ${p.display} 的配置", Toast.LENGTH_SHORT).show()
                    onSaved()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Toast.makeText(activity, e.message ?: "保存失败", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }
}
