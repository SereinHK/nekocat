package com.nekochat.chat

import androidx.annotation.StringRes
import com.nekochat.R
import org.json.JSONObject

/** 传输类型。显示名走资源文件，见 [labelRes]。 */
enum class TransportType(val id: String, @StringRes val labelRes: Int) {
    RFCOMM("rfcomm", R.string.transport_rfcomm),
    BLE("ble", R.string.transport_ble),
    WIFI("wifi", R.string.transport_wifi);

    companion object {
        fun fromId(id: String?): TransportType =
            entries.firstOrNull { it.id == id } ?: RFCOMM
    }
}

/** 非 Compose 场景下取传输方式的显示名（Compose 里直接用 `stringResource(labelRes)`）。 */
fun TransportType.labelOf(context: android.content.Context): String = context.getString(labelRes)

/**
 * 消息类型（既是点对点信封类型，也是聊天记录类型）。
 *
 * **注意 [PING] / [PONG] 目前是"只收不发"的**：接收端能处理（收到 PING 立刻回 PONG、
 * 收到 PONG 按心跳处理），但全工程没有任何一处**发送**它们 ——
 * 保活实际靠每 12 秒一次的 HELLO 重播（见 `MeshManager.HELLO_INTERVAL_MS`）。
 *
 * 保留这两个类型是为将来做链路活性探测留口子（HELLO 携带昵称、开销更大，
 * 而探测只需要一个空包）。**别误以为它已经在工作。**
 */
object MsgType {
    const val HELLO = "HELLO"
    const val CHAT = "CHAT"
    const val PING = "PING"
    const val PONG = "PONG"
}

/** 设备/昵称等节点信息。 */
data class DeviceInfo(
    val deviceId: String,
    val nickname: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("deviceId", deviceId)
        .put("nickname", nickname)

    companion object {
        fun fromJson(json: JSONObject): DeviceInfo = DeviceInfo(
            deviceId = json.optString("deviceId", ""),
            nickname = json.optString("nickname", "未知设备")
        )
    }
}

/** 一条聊天记录（本机展示用）。 */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
    /** 群发还是私聊（私聊时为目标 deviceId）。 */
    val targetId: String? = null,
    val system: Boolean = false,
    /** 已直连的接收方数量 / 需要投递的接收方数量。 */
    val deliveredCount: Int = 0,
    val expectedCount: Int = 0
)

/**
 * 线上信封。所有消息统一编码为 JSON 对象再按帧发送，便于日后扩展。
 */
class Envelope(
    val type: String,
    val sender: DeviceInfo,
    val text: String? = null,
    val targetId: String? = null,
    val msgId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("sender", sender.toJson())
        .put("timestamp", timestamp)
        .also { json ->
            text?.let { json.put("text", it) }
            targetId?.let { json.put("targetId", it) }
            msgId?.let { json.put("msgId", it) }
        }

    override fun toString(): String = toJson().toString()

    companion object {
        fun fromJson(json: JSONObject): Envelope? {
            val type = json.optString("type").ifEmpty { return null }
            val senderJson = json.optJSONObject("sender") ?: return null
            return Envelope(
                type = type,
                sender = DeviceInfo.fromJson(senderJson),
                text = json.optString("text").ifEmpty { null },
                targetId = json.optString("targetId").ifEmpty { null },
                msgId = json.optString("msgId").ifEmpty { null },
                timestamp = json.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun decode(payload: ByteArray): Envelope? = try {
            fromJson(JSONObject(String(payload, Charsets.UTF_8)))
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 帧协议：4 字节大端长度 + UTF-8 JSON 负载。
 *
 * ```
 * +--------+--------+--------+--------+
 * |      payload length (uint32)      |
 * +--------+--------+--------+--------+
 * |           payload bytes           |
 * +-----------------------------------+
 * ```
 */

/** 单帧负载上限，防止异常数据导致内存膨胀。 */
const val MAX_FRAME_PAYLOAD = 64 * 1024

object Wire {

    /** 单帧负载上限（兼容旧引用）。 */
    const val MAX_PAYLOAD = MAX_FRAME_PAYLOAD

    fun encode(env: Envelope): ByteArray = encode(env.toJson().toString().toByteArray(Charsets.UTF_8))

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = ((payload.size ushr 24) and 0xFF).toByte()
        out[1] = ((payload.size ushr 16) and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }
}

/**
 * 增量帧解码器：喂入任意大小的字节块，产出完整信封。
 * 适用于 BLE 分片与 RFCOMM 流式读取两种场景。
 */
class FrameDecoder {

    private var buffer = ByteArray(8192)
    private var size = 0
    /**
     * 已解析出长度、正在等待剩余负载。
     *
     * 为 **0** 表示尚未读出长度头（不同于 -1，后者已不再使用）。
     */
    private var expected: Int = 0

    @Synchronized
    fun reset() {
        size = 0
        expected = 0
    }

    @Synchronized
    fun feed(data: ByteArray, length: Int = data.size): List<Envelope> {
        if (length <= 0) return emptyList()
        ensure(size + length)
        data.copyInto(buffer, size, 0, length)
        size += length

        val result = ArrayList<Envelope>(2)
        while (size >= 4) {
            if (expected == 0) {
                val len = readLengthAt(0)
                if (len <= 0 || len > MAX_FRAME_PAYLOAD) {
                    // 长度头本身就不合法：丢掉这 1 个字节，继续从下一个位置找帧边界。
                    //
                    // 这里**不能清空整个缓存**：字节流里一处错位之后，
                    // 缓冲区后面往往还躺着完好帧；清空会把它们一起丢掉。
                    consume(1)
                    continue
                }
                expected = len
                consume(4)
            }
            if (size < expected) break
            val env = Envelope.decode(buffer.copyOfRange(0, expected))
            consume(expected)
            expected = 0
            if (env != null) {
                result.add(env)
            } else {
                // 长度头"合法"但内容不是 JSON —— 说明这个头其实是垃圾数据。
                //
                // 旧实现在这里什么都不做，等于**信任了那个长度**并把后续 expected 字节
                // 全部吃掉，于是紧随其后的合法帧被连累丢弃（实测：声明 64KB 只到 1 字节、
                // 或声明 32 字节的垃圾头，都会让后面的正常帧消失且毫无提示）。
                // 现在改为**回退**：把这批负载按"可能是下一帧的长度头"重新逐字节扫描。
                // 字节流错位后能自愈，代价只是错位那一次要多扫几十到几万字节。
                consume(1)
            }
        }
        return result
    }

    /** 读取缓冲区 [offset] 处的 4 字节大端长度头。 */
    private fun readLengthAt(offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    /** 丢弃 [count] 字节并把剩余数据前移。 */
    private fun consume(count: Int) {
        val rest = size - count
        if (rest > 0) buffer.copyInto(buffer, 0, count, size)
        size = rest
    }

    private fun ensure(capacity: Int) {
        if (capacity <= buffer.size) return
        var cap = buffer.size
        while (cap < capacity) cap = cap shl 1
        buffer = buffer.copyOf(cap)
    }
}
