package com.nekochat.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nekochat.NekoChatApp
import com.nekochat.MainActivity
import com.nekochat.R

/**
 * 前台服务：让连接在应用退到后台时继续存活。
 *
 * 说明：连接状态由 [com.nekochat.chat.MeshManager] 持有，而它是在
 * [com.nekochat.chat.ChatViewModel] 里构造的（**不是**进程级单例）。
 * 服务只能提升进程优先级，无法在 ViewModel 被清理后让组网继续工作 ——
 * 所以组件消失时它也必须停掉，否则会留下一个内容永远不再更新的常驻通知。
 */
class ChatForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 立刻提升为前台服务。
     *
     * 必须放在 [onCreate]：系统通过 `startForegroundService()` 拉起服务后，
     * 只给约 5 秒窗口调用 `startForeground()`，超时会抛
     * `ForegroundServiceDidNotStartInTimeException` 直接杀掉进程。
     * 放在 [onStartCommand] 里一旦被延迟（主线程忙）就会踩到这个超时。
     */
    override fun onCreate() {
        super.onCreate()
        promoteToForeground(getString(R.string.notif_title))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 走到这里就说明指令已经送达（无论来自通知按钮还是别处）。
            //
            // 注意：**停止服务不能依赖 startService 送达指令**。
            // API 26 起后台启动 Service 会被拒（IllegalStateException），
            // 而从最近任务划掉应用、或在后台点通知按钮，都属于后台场景。
            // 因此"停止"走两条独立路径：
            //   1. ChatForegroundService.stop() → context.stopService()，不经过这里；
            //   2. 通知按钮的 PendingIntent → 本分支，兜底清理。
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 带上当前状态刷新通知文案；失败则让服务退出，避免变成无前台的服务
        if (!promoteToForeground(intent?.getStringExtra(EXTRA_TEXT).orEmpty())) {
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** @return 是否成功进入前台。 */
    private fun promoteToForeground(text: String): Boolean {
        val notification = runCatching {
            buildNotification(text.ifBlank { getString(R.string.notif_waiting) })
        }.getOrNull()
        if (notification == null) {
            stopSelf()
            return false
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse {
            // 无法进入前台时主动收尾：否则系统会因「启动了前台服务却没调用 startForeground」而杀进程
            stopSelf()
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ChatForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NekoChatApp.CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.nekochat.STOP_SERVICE"
        const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, ChatForegroundService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            // **必须用 stopService，而不是 startService(ACTION_STOP)。**
            //
            // 本应用 targetSdk 37，而 API 26 起在后台调用 startService() 会抛
            // IllegalStateException —— 从最近任务划掉应用时 onCleared() 正是在后台执行，
            // 于是"停止服务"的指令根本送不到（还被 runCatching 静默吞掉），
            // 结果是 mesh 已经结束、前台服务和常驻通知却继续挂着。
            // stopService 是直接停服务，不受后台启动限制。
            runCatching { context.stopService(Intent(context, ChatForegroundService::class.java)) }
        }
    }
}
