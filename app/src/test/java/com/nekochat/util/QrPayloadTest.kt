package com.nekochat.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 二维码连接的编解码测试。
 *
 * 除了纯逻辑往返，还支持**解码真机截图**：把手机上「显示二维码」的截图放到
 * 系统临时目录并命名为 `nekochat-qr.png`，这个测试会把它解出来并比对内容 ——
 * 这样就能验证「生成端」真的产出了可被扫描的码，而不是只验证了自己跟自己往返。
 */
class QrPayloadTest {

    private val endpoint = QrPayload.Endpoint(
        host = "192.168.31.52",
        port = 45678,
        deviceId = "62b86b2e-e220-4883-9dfa-70ddc1638cf1",
        nickname = "手机"
    )

    @Test
    fun `encode then decode returns the same endpoint`() {
        val text = QrPayload.encode(endpoint)
        val decoded = QrPayload.decode(text)

        assertNotNull("应当能解析自己生成的连接码", decoded)
        assertEquals(endpoint.host, decoded!!.host)
        assertEquals(endpoint.port, decoded.port)
        assertEquals(endpoint.deviceId, decoded.deviceId)
        assertEquals(endpoint.nickname, decoded.nickname)
    }

    @Test
    fun `display shows host and port`() {
        assertEquals("192.168.31.52:45678", endpoint.display)
    }

    @Test
    fun `rejects payloads that are not ours`() {
        assertNull("普通文本不该被当成连接码", QrPayload.decode("https://example.com"))
        assertNull("JSON 但缺标记", QrPayload.decode("""{"h":"1.2.3.4","p":1}"""))
    }

    @Test
    fun `rejects payload with missing or invalid fields`() {
        // 缺 host
        assertNull(QrPayload.decode("""{"t":"nekochat","v":1,"p":45678}"""))
        // 端口越界
        assertNull(QrPayload.decode("""{"t":"nekochat","v":1,"h":"1.2.3.4","p":70000}"""))
    }

    @Test
    fun `nickname falls back to host when absent`() {
        val decoded = QrPayload.decode("""{"t":"nekochat","v":1,"h":"10.0.0.2","p":45678}""")
        assertNotNull(decoded)
        assertEquals("10.0.0.2", decoded!!.nickname)
    }

    @Test
    fun `generated matrix is a scannable QR code`() {
        val matrix = QrPayload.toMatrix(QrPayload.encode(endpoint), 512)
        assertNotNull("应能生成二维码点阵", matrix)

        val height = matrix!!.size
        val width = matrix[0].size
        // 从点阵还原成 ARGB（黑=0xFF000000，白=0xFFFFFFFF），再走一遍解码
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }

        val source = RGBLuminanceSource(width, height, pixels)
        val result = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }.decode(BinaryBitmap(HybridBinarizer(source)))

        val decoded = QrPayload.decode(result.text)
        assertNotNull("自己生成的二维码应当能被自己扫出来", decoded)
        assertEquals(endpoint.host, decoded!!.host)
        assertEquals(endpoint.port, decoded.port)
        assertEquals(endpoint.deviceId, decoded.deviceId)
        assertEquals(endpoint.nickname, decoded.nickname)
    }

    @Test
    fun `matrix is square and rejects non-positive size`() {
        val matrix = QrPayload.toMatrix(QrPayload.encode(endpoint), 256)
        assertNotNull(matrix)
        assertEquals("二维码应当方正", matrix!!.size, matrix[0].size)
        assertNull("尺寸非法时返回 null", QrPayload.toMatrix("x", 0))
    }

    /**
     * 解码真机截图（存在才跑）。
     *
     * 覆盖的是「生成路径 + 真实屏幕渲染」—— 位图经过 PNG 编解码、
     * 缩放和屏幕反锯齿之后仍然可扫，才算真的可用。
     */
    @Test
    fun `decodes a real device screenshot when provided`() {
        val shot = File(System.getProperty("java.io.tmpdir"), "nekochat-qr.png")
        Assume.assumeTrue("未提供真机截图，跳过", shot.exists())

        val image: BufferedImage = ImageIO.read(shot)
        assertNotNull("截图无法读取", image)

        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getRGB(0, 0, width, height, pixels, 0, width)

        val source = RGBLuminanceSource(width, height, pixels)
        val result = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }.decode(BinaryBitmap(HybridBinarizer(source)))

        val decoded = QrPayload.decode(result.text)
        assertNotNull("截图里的二维码应当是本应用的连接码", decoded)
        println("截图解出：${decoded!!.nickname} @ ${decoded.display} id=${decoded.deviceId}")
    }
}
