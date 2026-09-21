package com.nekochat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息标签行显示规则的测试。
 *
 * 私聊与群发的**视觉效果**需要两台设备才能人工确认，
 * 但「什么时候该显示那行标签（发送者 / 私聊 + 锁图标）」是纯逻辑，
 * 不该靠肉眼看着对来保证 —— 尤其是「私聊模式下标签被整行藏掉」
 * 这种会让用户分不清消息类型的回退。
 */
class MessageLabelTest {

    @Test
    fun `incoming group message shows sender on group start`() {
        assertTrue(
            shouldShowMessageLabel(
                outgoing = false, system = false, targetId = null, isGroupStart = true
            )
        )
    }

    @Test
    fun `outgoing broadcast hides its own name`() {
        // 自己的名字没有信息量，发出的群发不显示标签
        assertFalse(
            shouldShowMessageLabel(
                outgoing = true, system = false, targetId = null, isGroupStart = true
            )
        )
    }

    @Test
    fun `outgoing private message shows the recipient`() {
        // 必须显示，否则不知道悄悄话发给了谁
        assertTrue(
            shouldShowMessageLabel(
                outgoing = true, system = false, targetId = "dev-b", isGroupStart = true
            )
        )
    }

    @Test
    fun `incoming private message shows the sender`() {
        assertTrue(
            shouldShowMessageLabel(
                outgoing = false, system = false, targetId = "dev-b", isGroupStart = true
            )
        )
    }

    @Test
    fun `private label must not be hidden merely because a private chat is active`() {
        // 这条是回归测试：曾经用「是否处于私聊模式」来决定显示与否，
        // 结果私聊模式下整行标签（含「私聊」+ 锁图标）都被藏掉，
        // 用户无法分辨哪条是私聊。私聊消息在任何情况下都必须能看出来。
        for (outgoing in listOf(true, false)) {
            assertTrue(
                "outgoing=$outgoing 的私聊消息必须显示标签",
                shouldShowMessageLabel(
                    outgoing = outgoing, system = false,
                    targetId = "dev-b", isGroupStart = true
                )
            )
        }
    }

    @Test
    fun `subsequent messages in a group repeat no label`() {
        for (outgoing in listOf(true, false)) {
            assertFalse(
                shouldShowMessageLabel(
                    outgoing = outgoing, system = false,
                    targetId = "dev-b", isGroupStart = false
                )
            )
        }
    }

    @Test
    fun `system messages never show a label`() {
        assertFalse(
            shouldShowMessageLabel(
                outgoing = false, system = true, targetId = null, isGroupStart = true
            )
        )
    }
}
