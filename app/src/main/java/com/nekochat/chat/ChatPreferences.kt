package com.nekochat.chat

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * 轻量级本地设置存储（昵称、设备 ID、传输方式等）。
 *
 * 声明为 open 是为了让单元测试能用固定身份替换它，从而在不依赖真机的情况下
 * 验证组网与协议逻辑。
 */
open class ChatPreferences(context: Context) {

    /**
     * 允许为 null：设置存储不可用时退化为内存态，而不是直接崩溃。
     * 单元测试也会传入无法提供 SharedPreferences 的假 Context。
     */
    private val prefs: SharedPreferences? =
        runCatching { context.getSharedPreferences("neko_chat_prefs", Context.MODE_PRIVATE) }.getOrNull()

    /** 本机稳定 ID，首次启动生成后不再改变。 */
    open val deviceId: String
        get() = prefs?.getString(KEY_DEVICE_ID, null) ?: fallbackId.also {
            prefs?.edit()?.putString(KEY_DEVICE_ID, it)?.apply()
        }

    /** 没有持久化时用进程内固定值兜底，保证同一次运行内身份稳定。 */
    private val fallbackId: String by lazy { UUID.randomUUID().toString() }

    open var nickname: String
        get() = prefs?.getString(KEY_NICKNAME, null) ?: defaultNickname()
        set(value) {
            prefs?.edit()?.putString(KEY_NICKNAME, value.ifBlank { defaultNickname() })?.apply()
        }

    open var transport: TransportType
        get() = TransportType.fromId(prefs?.getString(KEY_TRANSPORT, null))
        set(value) {
            prefs?.edit()?.putString(KEY_TRANSPORT, value.id)?.apply()
        }

    /** 启动后自动连接上次的房间（本项目简化为自动启动服务）。 */
    open var autoStart: Boolean
        get() = prefs?.getBoolean(KEY_AUTO_START, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_AUTO_START, value)?.apply()
        }

    /** 新消息在后台时是否弹出通知。 */
    open var notifyOnMessage: Boolean
        get() = prefs?.getBoolean(KEY_NOTIFY, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_NOTIFY, value)?.apply()
        }

    private fun defaultNickname(): String =
        android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_NICKNAME = "nickname"
        const val KEY_TRANSPORT = "transport"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_NOTIFY = "notify_on_message"
    }
}
