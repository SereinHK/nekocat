package com.nekochat.bluetooth

import com.nekochat.chat.DeviceInfo
import com.nekochat.chat.Envelope
import com.nekochat.chat.FrameDecoder
import com.nekochat.chat.MsgType
import com.nekochat.chat.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE 分片大小测试。
 *
 * 真机上「发一条长消息，发完就掉线」的根因就在这里：单次 GATT 传输受 ATT MTU 限制，
 * 可用的负载只有 `MTU − 3` 字节，而代码里把分片大小写死成 160、两处 `onMtuChanged`
 * 都只打日志不落地。短消息没超过上限所以看起来正常，长消息第一片就超了 ——
 * 写失败会主动断开链路，于是表现为「发完掉线」。
 *
 * 这个函数是纯的，所以直接锁住它的不变量，不用真机也能防回归。
 */
class BleChunkTest {

    /** 最要紧的一条：任何 MTU 下都不能算出超过单次传输上限的分片。 */
    @Test
    fun `分片永远不超过单次 GATT 传输上限`() {
        for (mtu in 23..517) {
            val size = BleTransport.chunkSizeFor(mtu)
            assertTrue(
                "MTU=$mtu 时算出单片 $size 字节，超过上限 ${mtu - 3}",
                size <= mtu - 3
            )
            assertTrue("MTU=$mtu 时算出单片 $size 字节，小于协议允许的最小值", size >= 20)
        }
    }

    /** MTU 还没协商出来时必须保守：按默认 23 算，宁可多发几片也不能超。 */
    @Test
    fun `未协商时按默认 MTU 保守处理`() {
        assertEquals(20, BleTransport.chunkSizeFor(BleTransport.DEFAULT_ATT_MTU))
    }

    /** 协商到很大的 MTU 也不要一次发太多，仍受 CHUNK_SIZE 约束。 */
    @Test
    fun `大 MTU 仍受分片上限约束`() {
        assertEquals(BleTransport.CHUNK_SIZE, BleTransport.chunkSizeFor(512))
        assertEquals(BleTransport.CHUNK_SIZE, BleTransport.chunkSizeFor(517))
        assertEquals(BleTransport.CHUNK_SIZE, BleTransport.chunkSizeFor(Int.MAX_VALUE))
    }

    /** 异常 MTU（协议栈偶尔会报 0 或负值）不能算出非法分片。 */
    @Test
    fun `异常 MTU 退回最小值`() {
        assertEquals(20, BleTransport.chunkSizeFor(0))
        assertEquals(20, BleTransport.chunkSizeFor(-1))
        assertEquals(20, BleTransport.chunkSizeFor(3))
        assertEquals(20, BleTransport.chunkSizeFor(Int.MIN_VALUE))
    }

    /** 常见的几档 MTU 各算一遍，防止 coerceIn 的边界写反。 */
    @Test
    fun `常见 MTU 的分片大小符合预期`() {
        assertEquals(20, BleTransport.chunkSizeFor(23))    // 默认 MTU
        assertEquals(97, BleTransport.chunkSizeFor(100))
        assertEquals(160, BleTransport.chunkSizeFor(163))  // 刚好到上限
        assertEquals(160, BleTransport.chunkSizeFor(185))
    }

    // ------------------------------------------------------------------
    // 分片 → 重组 的往返
    //
    // 这一组盯的是「发出去切了片、收回来没拼」这一类问题。真机上就踩过：
    // 服务端开始按 MTU 分片之后，客户端仍把每一片当整帧解析，
    // 于是长消息全部报「无法解析的数据包」——第一片的前缀
    // `00 00 01 d3 7b 22 74 79`（长度头 + `{"ty`）明明是对的。
    // 这类逻辑纯得不需要真机，所以直接在这里锁死。
    // ------------------------------------------------------------------

    /** 模拟发送方：按当前 MTU 把整帧切片。 */
    private fun slice(frame: ByteArray, mtu: Int): List<ByteArray> {
        val size = BleTransport.chunkSizeFor(mtu)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + size, frame.size)
            chunks += frame.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    /** 模拟接收方：把收到的分片喂给解码器，返回解出来的帧。 */
    private fun reassemble(chunks: List<ByteArray>): List<Envelope> {
        val decoder = FrameDecoder()
        val out = mutableListOf<Envelope>()
        chunks.forEach { out += decoder.feed(it) }
        return out
    }

    @Test
    fun `长帧分片后能完整重组`() {
        val text = "长消息测试".repeat(200)   // UTF-8 下 1200 字节，必然多片
        val original = Envelope(
            type = MsgType.CHAT,
            sender = DeviceInfo("dev-a", "甲"),
            text = text,
            msgId = "m-1"
        )
        val frame = Wire.encode(original)
        assertTrue("这个用例的意义在于帧必须跨多片，现在只有 ${frame.size} 字节", frame.size > 200)

        listOf(23, 60, 100, 185, 512).forEach { mtu ->
            val chunks = slice(frame, mtu)
            assertTrue("MTU=$mtu 时应该切成多片", chunks.size > 1)
            val decoded = reassemble(chunks)
            assertEquals("MTU=$mtu 应恰好解出 1 帧", 1, decoded.size)
            assertEquals("MTU=$mtu 文本被改动了", text, decoded[0].text)
            assertEquals("MTU=$mtu 的 msgId 丢了", "m-1", decoded[0].msgId)
            assertEquals("MTU=$mtu 的发送者丢了", "dev-a", decoded[0].sender.deviceId)
        }
    }

    /** 短帧（单片）也要能走同一条路径 —— HELLO 就属于这种。 */
    @Test
    fun `单片帧同样能解出`() {
        val hello = Envelope(type = MsgType.HELLO, sender = DeviceInfo("dev-b", "乙"))
        val frame = Wire.encode(hello)
        listOf(23, 512).forEach { mtu ->
            val chunks = slice(frame, mtu)
            val decoded = reassemble(chunks)
            assertEquals("MTU=$mtu 应解出 1 帧", 1, decoded.size)
            assertEquals("dev-b", decoded[0].sender.deviceId)
        }
    }

    /**
     * 一条链路上连续发多帧时，分片不能串到一起。
     * 三台设备的场景下同一链路会连着发 HELLO、群聊、私聊，这条最容易出问题。
     */
    @Test
    fun `连续多帧分片后逐帧解出且不串帧`() {
        val originals = listOf(
            Envelope(MsgType.HELLO, DeviceInfo("dev-a", "甲")),
            Envelope(MsgType.CHAT, DeviceInfo("dev-a", "甲"), text = "A".repeat(300), msgId = "m-1"),
            Envelope(MsgType.HELLO, DeviceInfo("dev-b", "乙")),
            Envelope(MsgType.CHAT, DeviceInfo("dev-b", "乙"), text = "B".repeat(500), msgId = "m-2")
        )
        listOf(23, 100, 512).forEach { mtu ->
            val allChunks = originals.flatMap { slice(Wire.encode(it), mtu) }
            val decoded = reassemble(allChunks)
            assertEquals("MTU=$mtu 解出的帧数不对", originals.size, decoded.size)
            originals.forEachIndexed { i, expected ->
                assertEquals("MTU=$mtu 第 $i 帧的 msgId 串了", expected.msgId, decoded[i].msgId)
                assertEquals("MTU=$mtu 第 $i 帧的文本串了", expected.text, decoded[i].text)
            }
        }
    }
}
