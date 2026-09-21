package com.nekochat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧协议测试：验证 4 字节长度前缀 + JSON 信封的编解码，
 * 以及流式场景下的分片与粘包处理。
 *
 * 这是 RFCOMM 与 BLE 共用的底层协议，出问题会导致所有消息乱掉。
 */
class ProtocolTest {

    private fun envelope(text: String, id: String = "m1") = Envelope(
        type = MsgType.CHAT,
        sender = DeviceInfo("dev-a", "甲"),
        text = text,
        msgId = id
    )

    @Test
    fun `encode then decode round trips all fields`() {
        val original = Envelope(
            type = MsgType.CHAT,
            sender = DeviceInfo("dev-a", "甲"),
            text = "你好，世界",
            targetId = "dev-b",
            msgId = "id-123"
        )
        val frame = Wire.encode(original)
        val payload = frame.copyOfRange(4, frame.size)
        val decoded = Envelope.decode(payload)

        assertNotNull(decoded)
        assertEquals(MsgType.CHAT, decoded!!.type)
        assertEquals("dev-a", decoded.sender.deviceId)
        assertEquals("甲", decoded.sender.nickname)
        assertEquals("你好，世界", decoded.text)
        assertEquals("dev-b", decoded.targetId)
        assertEquals("id-123", decoded.msgId)
    }

    @Test
    fun `length prefix is big endian and matches payload size`() {
        val frame = Wire.encode(envelope("abc"))
        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, declared)
    }

    @Test
    fun `decoder handles a frame split across many chunks`() {
        val decoder = FrameDecoder()
        val frame = Wire.encode(envelope("被切成很多片的正文内容"))
        val out = mutableListOf<Envelope>()

        // 逐字节喂入，模拟 BLE 分片到达
        frame.forEach { byte ->
            out += decoder.feed(byteArrayOf(byte))
        }

        assertEquals(1, out.size)
        assertEquals("被切成很多片的正文内容", out[0].text)
    }

    @Test
    fun `decoder handles two frames coalesced in one chunk`() {
        val decoder = FrameDecoder()
        val chunk = Wire.encode(envelope("第一条", "a")) + Wire.encode(envelope("第二条", "b"))

        val out = decoder.feed(chunk)

        assertEquals(2, out.size)
        assertEquals("第一条", out[0].text)
        assertEquals("第二条", out[1].text)
    }

    @Test
    fun `decoder recovers after an illegal length`() {
        val decoder = FrameDecoder()
        // 声明一个超过上限的长度，解码器应清空缓冲而不是尝试分配
        val bogus = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)

        assertTrue(decoder.feed(bogus).isEmpty())

        // 之后仍能正常解析新帧
        val out = decoder.feed(Wire.encode(envelope("恢复后")))
        assertEquals(1, out.size)
        assertEquals("恢复后", out[0].text)
    }

    @Test
    fun `unknown or malformed payload yields null instead of throwing`() {
        assertNull(Envelope.decode("not json at all".toByteArray()))
        assertNull(Envelope.decode("""{"noType":1}""".toByteArray()))
        assertNull(Envelope.decode("""{"type":"CHAT"}""".toByteArray())) // 缺 sender
    }

    @Test
    fun `empty frame payload is rejected`() {
        val decoder = FrameDecoder()
        assertTrue(decoder.feed(Wire.encode(ByteArray(0))).isEmpty())
    }
}
