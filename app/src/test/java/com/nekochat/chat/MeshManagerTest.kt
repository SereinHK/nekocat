package com.nekochat.chat

import android.content.Context
import android.os.Handler
import com.nekochat.bluetooth.PeerLink
import com.nekochat.bluetooth.TransportListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 组网逻辑测试：用内存回环链路把两个 [MeshManager] 对接起来，
 * 验证握手、群聊投递、中继去重、私聊寻址。
 *
 * 这些逻辑决定了「消息能否正确到达、且不重复」，而它们此前完全没有被验证过。
 * 测试不需要真机，也不需要蓝牙栈。
 */
class MeshManagerTest {

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 不触碰 SharedPreferences 与蓝牙的固定身份。 */
    private class FakePrefs(
        private val id: String,
        private var name: String,
        override var transport: TransportType = TransportType.BLE
    ) : ChatPreferences(mock(Context::class.java)) {
        override val deviceId: String get() = id
        override var nickname: String
            get() = name
            set(value) {
                name = value
            }
        override var autoStart: Boolean = true
        override var notifyOnMessage: Boolean = true
    }

    /**
     * 立即执行 post 的 Handler。
     *
     * 必须显式 stub `post`：Mockito 对返回 boolean 的方法默认给 false，
     * 而 MeshManager 依赖 `handler.post { ... }` 发出首帧 HELLO，
     * 不 stub 就会静默丢弃，导致所有握手断言失败。
     */
    private fun directHandler(): Handler {
        val handler = mock(Handler::class.java)
        `when`(handler.post(any(Runnable::class.java))).thenAnswer { invocation ->
            invocation.getArgument<Runnable>(0).run()
            true
        }
        `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenAnswer {
            // 心跳在测试里由 broadcastHelloNow() 手动触发，不做循环调度
            true
        }
        return handler
    }

    /**
     * 内存回环链路：写入直接投递给对端的 listener，模拟一条理想链路。
     *
     * 必须真的回调 `listener.onFrame()`——只把字节存进列表不算投递，
     * 否则组网层永远收不到数据（这正是本测试第一版失败的原因）。
     */
    private class LoopbackLink(
        private val listener: TransportListener,
        override val address: String,
        override val displayName: String
    ) : PeerLink {
        override val transport = TransportType.BLE
        private var closed = false
        private var closeAction: (() -> Unit)? = null
        private var peer: LoopbackLink? = null
        val received = CopyOnWriteArrayList<ByteArray>()

        fun pairWith(other: LoopbackLink) {
            peer = other
            other.peer = this
        }

        override val isConnected: Boolean get() = !closed

        override fun write(frame: ByteArray, onComplete: (Boolean) -> Unit) {
            val target = peer
            if (closed || target == null || target.closed) {
                onComplete(false)
                return
            }
            target.received += frame
            // 按 PeerLink 契约投递：剥掉 4 字节长度前缀，只把 JSON 负载交给对端。
            // 真实传输层（RFCOMM 按长度读满 / BLE 用 FrameDecoder 重组）就是这么做的。
            if (frame.size >= 4) {
                val declared = ((frame[0].toInt() and 0xFF) shl 24) or
                    ((frame[1].toInt() and 0xFF) shl 16) or
                    ((frame[2].toInt() and 0xFF) shl 8) or
                    (frame[3].toInt() and 0xFF)
                val payload = frame.copyOfRange(4, minOf(4 + declared, frame.size))
                target.listener.onFrame(target, payload)
            }
            onComplete(true)
        }

        override fun onClosed(action: () -> Unit) {
            closeAction = action
        }

        override fun close() {
            if (closed) return
            closed = true
            closeAction?.invoke()
        }
    }

    private class RecordingListener : MeshManager.Listener {
        val peers = CopyOnWriteArrayList<List<Peer>>()
        val messages = CopyOnWriteArrayList<ChatMessage>()
        val statuses = CopyOnWriteArrayList<String>()

        override fun onPeersChanged(peers: List<Peer>) {
            this.peers += peers
        }

        override fun onMessage(message: ChatMessage) {
            messages += message
        }

        override fun onStatus(text: String) {
            statuses += text
        }

        override fun onMessageDelivery(id: String, delivered: Int, expected: Int) = Unit

        override fun onDeviceFound(device: DiscoveredDevice) = Unit

        val lastPeerCount: Int get() = peers.lastOrNull()?.size ?: 0

        /**
         * 等待「在线设备数达到 [atLeast]」的回调到达，最多等 [timeoutMs]。
         *
         * `onPeersChanged` 是经 handler 异步投递的，直接断言会和回调赛跑 ——
         * 这个测试原先就是这么写的，表现是偶尔失败（单独跑必过、全量跑偶尔挂）。
         * 事件驱动的断言不该假设回调已经执行完。
         */
        fun awaitPeerCount(atLeast: Int, timeoutMs: Long = 3_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (lastPeerCount >= atLeast) return true
                Thread.sleep(10)
            }
            return lastPeerCount >= atLeast
        }
    }

    // ------------------------------------------------------------------
    // 脚手架
    // ------------------------------------------------------------------

    /**
     * 只桩掉 `getString` 的 [Context]。
     *
     * 组网层的状态文案现在都走资源文件，直接 `mock(Context::class.java)` 会让
     * `getString` 返回 null（Kotlin 的非空平台类型立刻抛 NPE）。
     * 这里用默认答案统一兜底：`getString` 返回可识别的占位串，其余方法仍走
     * Mockito 的默认空值（这样基本类型返回值不会因为返回 null 而报错）。
     * 不断言具体文案 —— 文案属于资源，不属于组网逻辑。
     */
    private fun fakeContext(): Context = mock(
        Context::class.java,
        Answer { invocation ->
            if (invocation.method.name == "getString") {
                "res#" + invocation.arguments.firstOrNull()
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
    )

    private fun newManager(id: String, name: String): Pair<MeshManager, RecordingListener> {
        val manager = MeshManager(
            fakeContext(),
            FakePrefs(id, name),
            directHandler()
        )
        val listener = RecordingListener()
        manager.setListener(listener)
        return manager to listener
    }

    /** 建立两个节点之间的一条双向回环链路，并接入组网。 */
    private fun connect(a: MeshManager, b: MeshManager) {
        val linkForA = LoopbackLink(a, "AA:AA:AA:AA:AA:01", "peerB")
        val linkForB = LoopbackLink(b, "BB:BB:BB:BB:BB:02", "peerA")
        linkForA.pairWith(linkForB)
        a.attachLoopbackLink(linkForA)
        b.attachLoopbackLink(linkForB)
    }

    /**
     * 轮询等待对端出现在 [MeshManager.currentPeers] 中。
     *
     * HELLO 是在后台协程里写出的，`broadcastHelloNow()` 返回时对端**还没收到**，
     * 紧接着同步断言 peers 会随机失败（这个用例此前就一直偶发失败）。
     */
    private fun awaitPeers(manager: MeshManager, atLeast: Int, timeoutMs: Long = 3_000): List<Peer> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val peers = manager.currentPeers()
            if (peers.size >= atLeast) return peers
            Thread.sleep(10)
        }
        return manager.currentPeers()
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `hello handshake makes both sides aware of each other`() {
        val (a, la) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        connect(a, b)

        // 链路接入时会各发一次 HELLO；再手动补一次覆盖时序
        a.broadcastHelloNow()
        b.broadcastHelloNow()

        val peersA = awaitPeers(a, 1)
        assertEquals(1, peersA.size)
        assertEquals("乙", peersA[0].nickname)
        assertEquals("dev-b", peersA[0].deviceId)

        val peersB = awaitPeers(b, 1)
        assertEquals(1, peersB.size)
        assertEquals("甲", peersB[0].nickname)

        assertTrue(la.awaitPeerCount(1))
        assertTrue(lb.awaitPeerCount(1))
    }

    @Test
    fun `broadcast message reaches the peer exactly once`() {
        val (a, _) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        connect(a, b)
        a.broadcastHelloNow()
        b.broadcastHelloNow()

        val sent = a.sendBroadcast("广播测试")

        // 等待中继协程把消息写出（异步）
        val latch = CountDownLatch(1)
        Thread {
            val deadline = System.currentTimeMillis() + 3000
            while (lb.messages.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            latch.countDown()
        }.start()
        assertTrue("3 秒内未收到消息", latch.await(4, TimeUnit.SECONDS))

        val got = lb.messages.filter { it.text == "广播测试" }
        assertEquals("对端应当且只应当收到一次", 1, got.size)
        assertEquals(sent.id, got[0].id)
        assertEquals("甲", got[0].senderName)
        assertTrue(!got[0].outgoing)
    }

    @Test
    fun `relayed echo is deduplicated and not shown twice`() {
        val (a, la) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        connect(a, b)
        a.broadcastHelloNow()
        b.broadcastHelloNow()

        a.sendBroadcast("只应出现一次")

        // 等 B 收到并完成回声中继
        Thread.sleep(800)

        // B 会向 A 回传该消息（中继），A 必须靠 msgId 去重丢掉它
        val echoed = la.messages.filter { it.text == "只应出现一次" }
        assertEquals("本机不应把中继回来的消息再显示一次", 0, echoed.size)
        assertEquals(1, lb.messages.count { it.text == "只应出现一次" })
    }

    @Test
    fun `private message carries target id`() {
        val (a, la) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        connect(a, b)
        a.broadcastHelloNow()
        b.broadcastHelloNow()

        val sent = a.sendPrivate("dev-b", "悄悄话")

        Thread.sleep(800)

        assertEquals("dev-b", sent.targetId)
        val received = lb.messages.filter { it.text == "悄悄话" }
        assertEquals(1, received.size)
        assertEquals("dev-b", received[0].targetId)
        assertEquals(0, la.messages.count { it.text == "悄悄话" })
    }

    @Test
    fun `private message for someone else is ignored locally`() {
        val (a, la) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        connect(a, b)
        a.broadcastHelloNow()
        b.broadcastHelloNow()

        // 目标是不存在的第三方：链路按设备 ID 匹配，找不到目标时投递数应为 0
        a.sendPrivate("dev-zzz", "发给别人的")

        Thread.sleep(600)

        assertEquals("非目标节点不应展示该消息", 0, lb.messages.count { it.text == "发给别人的" })
        assertEquals(0, la.messages.count { it.text == "发给别人的" })
    }

    @Test
    fun `three nodes relay a broadcast to the third one`() {
        val (a, _) = newManager("dev-a", "甲")
        val (b, lb) = newManager("dev-b", "乙")
        val (c, lc) = newManager("dev-c", "丙")

        // A—B 直连，B—C 直连，A 与 C 不直连
        connect(a, b)
        connect(b, c)
        a.broadcastHelloNow()
        b.broadcastHelloNow()
        c.broadcastHelloNow()

        a.sendBroadcast("通过中继到达丙")

        Thread.sleep(1000)

        assertEquals("乙 应收到", 1, lb.messages.count { it.text == "通过中继到达丙" })
        assertEquals("丙 应通过乙中继收到", 1, lc.messages.count { it.text == "通过中继到达丙" })
    }

    @Test
    fun `transport type parsing round trips`() {
        assertEquals(TransportType.BLE, TransportType.fromId("ble"))
        assertEquals(TransportType.RFCOMM, TransportType.fromId("rfcomm"))
        assertEquals("未知 id 回退到 RFCOMM", TransportType.RFCOMM, TransportType.fromId("nonsense"))
    }
}
