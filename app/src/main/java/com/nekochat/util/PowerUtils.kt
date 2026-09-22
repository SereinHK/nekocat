package com.nekochat.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化相关的查询与跳转。
 *
 * **为什么需要**：真机实测过 —— 手机息屏几分钟后 GATT 链路直接被断开，
 * 界面显示「已断开」，而应用其实一直在前台服务里跑着。
 * 这是厂商的省电策略在杀后台网络，前台服务拦不住，唯一的办法是把这个应用
 * 加进系统的「不优化电池」白名单。
 *
 * 它不会让息屏断连彻底消失（有些 ROM 还有自己的额外限制），但这是唯一
 * 官方支持的手段，也比让用户自己去设置里翻要友好得多。
 */
object PowerUtils {

    /** 本机是否已经豁免电池优化。取不到系统服务时按「已豁免」处理，避免误报提示。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(true)
    }

    /**
     * 弹出系统的电池优化豁免请求。
     *
     * 优先用带包名的直接请求弹窗；个别 ROM 不认这个 Intent（或没有该 Activity），
     * 退回到「电池优化」列表页让用户自己选。两条路都失败就什么都不做，
     * 不要因为跳转失败把应用搞崩。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(direct) }.isSuccess) return

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(list) }
    }
}
