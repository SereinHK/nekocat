package com.nekochat.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/**
 * 连接信息的二维码编解码。
 *
 * 为什么用二维码而不是 UDP 广播发现：
 * - **确定性**：扫到就能连，不存在「广播被路由器拦了」这种静默失败
 * - 不需要 `MulticastLock`，也不受「客户端隔离」影响
 * - 可以顺带带上 deviceId，连接时能校验身份
 *
 * 用 ZXing 而非 ML Kit：**MatePad 没有 GMS**，ML Kit 依赖 Google Play 服务，装了也跑不起来。
 */
object QrPayload {

    /** 二维码内容里用于识别「这是喵聊的连接码」的标记。 */
    private const val MAGIC = "nekochat"

    /** 版本号：将来格式变了可据此做兼容。 */
    private const val VERSION = 1

    /** 生成二维码用的字符集提示（内容全是 ASCII，用 UTF-8 即可）。 */
    private val ENCODE_HINTS = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )

    private val DECODE_HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.TRY_HARDER to true
    )

    /** 连接信息。 */
    data class Endpoint(
        val host: String,
        val port: Int,
        val deviceId: String,
        val nickname: String
    ) {
        /** 供界面展示的地址文本。 */
        val display: String get() = "$host:$port"
    }

    /** 把连接信息编码成二维码文本。 */
    fun encode(endpoint: Endpoint): String = JSONObject().apply {
        put("t", MAGIC)
        put("v", VERSION)
        put("h", endpoint.host)
        put("p", endpoint.port)
        put("id", endpoint.deviceId)
        put("n", endpoint.nickname)
    }.toString()

    /**
     * 解析二维码文本。
     *
     * @return 解析成功返回 [Endpoint]；不是本应用的码或字段缺失时返回 null。
     */
    fun decode(text: String): Endpoint? {
        return runCatching {
            val json = JSONObject(text)
            if (json.optString("t") != MAGIC) return null
            val host = json.optString("h").ifBlank { return null }
            val port = json.optInt("p", 0).takeIf { it in 1..65535 } ?: return null
            Endpoint(
                host = host,
                port = port,
                deviceId = json.optString("id"),
                nickname = json.optString("n").ifBlank { host }
            )
        }.getOrNull()
    }

    /**
     * 生成二维码点阵。
     *
     * 与 [toBitmap] 分开是为了**可测试**：`Bitmap` 是 Android 类，
     * JVM 单元测试里所有方法都返回默认值（`createBitmap` 得到 null），
     * 拿它验证二维码不成立。点阵本身是纯 Java 计算，可以真正被断言。
     *
     * @return true 表示该位置为黑色
     */
    fun toMatrix(text: String, sizePx: Int): Array<BooleanArray>? {
        if (sizePx <= 0) return null
        return runCatching {
            val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, ENCODE_HINTS)
            Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y) } }
        }.getOrNull()
    }

    /** 生成二维码位图。 */
    fun toBitmap(text: String, sizePx: Int): Bitmap? {
        val pixels = toMatrix(text, sizePx) ?: return null
        val height = pixels.size
        val width = pixels.firstOrNull()?.size ?: return null
        return runCatching {
            val argb = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    argb[offset + x] = if (pixels[y][x]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(argb, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    }

    /**
     * 从相机预览帧中解析二维码。
     *
     * @param data Y 平面数据（[androidx.camera.core.ImageProxy] 的 Y 平面）
     * @param width 图像宽度
     * @param height 图像高度
     * @return 识别到的连接信息；没识别到返回 null
     */
    fun decodeYuv(data: ByteArray, width: Int, height: Int): Endpoint? {
        return runCatching {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().apply { setHints(DECODE_HINTS) }.decode(bitmap)
            decode(result.text)
        }.getOrNull()
    }
}
