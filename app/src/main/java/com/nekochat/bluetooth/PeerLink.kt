package com.nekochat.bluetooth

import com.nekochat.chat.TransportType
import java.io.Closeable

/** 传输层上的一条对等连接（RFCOMM socket 或 BLE GATT 通道）。 */
interface PeerLink : Closeable {

    /** 对端设备地址（MAC）。 */
    val address: String

    /** 对端展示名（可能是 MAC 或蓝牙设备名）。 */
    val displayName: String

    val transport: TransportType

    /** 当前是否可发送数据。 */
    val isConnected: Boolean

    /**
     * 写入一帧数据。
     * @param onComplete 写入完成回调（BLE 需要等待回调，失败时返回 false）
     */
    fun write(frame: ByteArray, onComplete: (Boolean) -> Unit)

    /** 底层链路断开时回调（仅触发一次）。 */
    fun onClosed(action: () -> Unit)
}

/** 面向 MeshManager 的传输后端回调。 */
interface TransportListener {
    /** 新链路建立（此时尚未完成昵称握手）。 */
    fun onLinkUp(link: PeerLink)

    /**
     * 收到一帧完整数据。
     *
     * **契约**：[frame] 是**去掉 4 字节长度前缀后的 JSON 负载**，不是整帧。
     * 传输层负责拆长度前缀（RFCOMM 按长度读满，BLE 用 FrameDecoder 重组），
     * 上层 [com.nekochat.chat.MeshManager] 直接把它当 JSON 解析。
     * 实现若把整帧传进来，会导致解析失败且消息被静默丢弃。
     */
    fun onFrame(link: PeerLink, frame: ByteArray)

    /** 链路断开。 */
    fun onLinkDown(link: PeerLink, reason: String?)
}
