package com.nekochat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 在**真实 TCP 连接**上验证帧协议。
 *
 * 为什么单独做这个测试：`ProtocolTest` 只验证解码器逻辑本身，
 * 而 TCP 是字节流，帧边界需要「读满 4 字节长度头 + 读满负载」来保证。
 * 这里用回环地址建真实 socket，覆盖这条路径 —— 不需要第二台设备，也不需要 UI。
 */
class TcpFramingTest {

    /** 与 [com.nekochat.bluetooth.TcpLink] 中相同的读写实现。 */
    private fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) throw IOException("连接已关闭")
            read += n
        }
        return buf
    }

    private fun readFrame(input: InputStream): Envelope? {
        val header = readFully(input, 4)
        val len = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (len <= 0 || len > MAX_FRAME_PAYLOAD) throw IOException("非法帧长度 $len")
        return Envelope.decode(readFully(input, len))
    }

    private fun envelope(text: String) = Envelope(
        type = MsgType.CHAT,
        sender = DeviceInfo("dev-a", "甲"),
        text = text
    )

    @Test
    fun `frames survive a real tcp round trip in both directions`() {
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = server.localPort

        val serverError = AtomicReference<String?>(null)
        val serverReceived = AtomicReference<String?>(null)
        val ready = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val socket = server.accept()
                socket.tcpNoDelay = true
                ready.countDown()
                // 先读客户端发来的帧
                serverReceived.set(readFrame(socket.getInputStream())?.text)
                // 再回一帧，验证双向
                val reply = Wire.encode(envelope("来自服务端的回复"))
                socket.getOutputStream().write(reply)
                socket.getOutputStream().flush()
                socket.close()
            } catch (e: Exception) {
                serverError.set(e.message)
            }
        }.apply { isDaemon = true; start() }

        // 客户端
        val client = Socket()
        client.tcpNoDelay = true
        client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        assertTrue("服务端应已 accept", ready.await(5, TimeUnit.SECONDS))

        client.getOutputStream().write(Wire.encode(envelope("你好，TCP")))
        client.getOutputStream().flush()

        val reply = readFrame(client.getInputStream())
        client.close()
        serverThread.join(5_000)
        server.close()

        assertEquals(null, serverError.get())
        assertEquals("你好，TCP", serverReceived.get())
        assertEquals("来自服务端的回复", reply?.text)
    }

    @Test
    fun `length prefix keeps frame boundaries when written in one burst`() {
        // 一次写入多帧，接收端必须能正确切分（TCP 会合并小包）
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = server.localPort

        val got = mutableListOf<String>()
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)

        val serverThread = Thread {
            try {
                val socket = server.accept()
                val input = socket.getInputStream()
                repeat(3) { got.add(readFrame(input)?.text ?: "?") }
                done.countDown()
                socket.close()
            } catch (e: Exception) {
                error.set(e.message)
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        val client = Socket()
        client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        val burst = Wire.encode(envelope("第一条")) +
            Wire.encode(envelope("第二条")) +
            Wire.encode(envelope("第三条"))
        client.getOutputStream().write(burst)
        client.getOutputStream().flush()

        assertTrue("应收到 3 帧", done.await(5, TimeUnit.SECONDS))
        client.close()
        serverThread.join(5_000)
        server.close()

        assertEquals(null, error.get())
        assertEquals(listOf("第一条", "第二条", "第三条"), got)
    }

    @Test
    fun `large payload beyond one tcp segment is reassembled`() {
        val server = ServerSocket()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = server.localPort

        // 约 40KB：远超单个 TCP 段，必然分片
        val big = "喵".repeat(13_000)
        val serverReceived = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)
        val done = CountDownLatch(1)

        val serverThread = Thread {
            try {
                val socket = server.accept()
                serverReceived.set(readFrame(socket.getInputStream())?.text)
                socket.close()
            } catch (e: Exception) {
                error.set(e.message)
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        val client = Socket()
        client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        client.getOutputStream().write(Wire.encode(envelope(big)))
        client.getOutputStream().flush()

        assertTrue("应收到大帧", done.await(10, TimeUnit.SECONDS))
        client.close()
        serverThread.join(5_000)
        server.close()

        assertEquals(null, error.get())
        assertEquals(big.length, serverReceived.get()?.length)
        assertEquals(big, serverReceived.get())
    }
}
