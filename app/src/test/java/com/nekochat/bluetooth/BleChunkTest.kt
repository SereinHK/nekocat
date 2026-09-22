package com.nekochat.bluetooth

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
}
