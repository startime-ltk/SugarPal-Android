package com.sugarpal.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.sugarpal.app.MainActivity
import com.sugarpal.app.R
import java.util.Locale

/**
 * 本地报警通知助手（P0-1）。
 *
 * 录入/导入后即时触发：系统通知（高优先级渠道）+ 震动。通知点击回到主界面。
 * API 33+ 未授予通知权限时静默降级，不打断录入流程。
 */
object NotificationHelper {

    private const val CHANNEL_ID = "sugar_alarm"
    private const val CHANNEL_NAME = "血糖异常报警"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "血糖值超出报警阈值时提醒"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun notifyAlarm(context: Context, value: Double, low: Double, high: Double, isLow: Boolean) {
        ensureChannel(context)
        val title = if (isLow) "血糖偏低提醒" else "血糖偏高提醒"
        val text = String.format(
            Locale.US,
            "本次血糖 %.1f mmol/L（报警阈值：偏低 < %.1f、偏高 > %.1f），请注意监测并及时处理。",
            value, low, high
        )

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // API 33+ 未授权通知权限时静默降级
        }
    }

    /** 导入完成后批量报警的汇总通知 */
    fun notifyBatchAlarm(context: Context, lowCount: Int, highCount: Int, low: Double, high: Double) {
        if (lowCount <= 0 && highCount <= 0) return
        ensureChannel(context)
        val title = "本次导入含异常血糖记录"
        val text = String.format(
            Locale.US,
            "导入记录中偏低 %d 条（< %.1f）、偏高 %d 条（> %.1f），可到「健康摘要」查看详情。",
            lowCount, low, highCount, high
        )

        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID + 1, builder.build())
        } catch (e: SecurityException) {
            // 同上，权限未授予时静默降级
        }
    }

    fun vibrate(context: Context) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(400)
        }
    }
}
