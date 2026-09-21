package com.nekochat.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.UUID

/**
 * 拨号规则测试。
 *
 * 首次相遇时「谁主动拨号」是**只有一方能做的决定**，判错任何一种都会断连：
 * 双方都拨 → GATT 双主互抢；双方都不拨 → 永久僵死且没有任何错误提示。
 * 所以这里主要验证这个判断的**反对称性**：把两侧的视角代入同一个函数，
 * 结果必须恰好相反。
 */
class DialRuleTest {

    private fun token(id: String) = MeshManager.dialTokenOf(id)

    /** 模拟「A 主动连 B」这一个方向上 A 的判定。 */
    private fun aDials(idA: String, idB: String, macB: String) =
        MeshManager.dialDecision(token(idA), token(idB), idA, macB)

    /** 模拟同一个方向上 B 的判定（B 看到的是 A 的 MAC）。 */
    private fun bDials(idA: String, idB: String, macA: String) =
        MeshManager.dialDecision(token(idB), token(idA), idB, macA)

    @Test
    fun `任意两台设备之间必然恰好一方拨号`() {
        val random = Random(20240921L)
        var connected = 0
        repeat(3_000) {
            val idA = UUID.randomUUID().toString()
            val idB = UUID.randomUUID().toString()
            if (token(idA) == token(idB)) return@repeat // 标识撞车，另有兜底规则，单独验证
            val macA = randomMac(random)
            val macB = randomMac(random)
            assertNotEquals(
                "idA=$idA(idB=$idB) 双方判定相同，会导致互抢或僵死",
                aDials(idA, idB, macB),
                bDials(idA, idB, macA)
            )
            connected++
        }
        assertTrue("绝大多数样本都应参与断言，实际 $connected", connected > 2_900)
    }

    @Test
    fun `与 MAC 地址无关`() {
        val idA = "6c9f1a20-1111-4000-8000-000000000001"
        val idB = "3b7d4e10-2222-4000-8000-000000000002"
        val macA = "6B:66:77:88:99:AA"
        // 同一对设备，换一组完全不同的 MAC，判定结果不应改变
        assertEquals(aDials(idA, idB, "6A:11:22:33:44:55"), aDials(idA, idB, "00:11:22:33:44:55"))
        assertNotEquals(aDials(idA, idB, "6A:11:22:33:44:55"), bDials(idA, idB, macA))
    }

    /**
     * 真机僵死配置的回归证据。
     *
     * 两台设备的 MAC 都以 `6` 开头（地址仅保留首字符特征，其余为构造值）；
     * 当两个 deviceId 都以 `2` 开头时，旧规则（本机 deviceId 拼对端 MAC 再比较）
     * 在两侧同时取负 —— 两台设备的日志都只有「跳过拨号……本机 ID 较小」，
     * 始终没有「开始拨号」，连接永远建立不起来。
     */
    @Test
    fun `旧规则在真机那组输入上双方都不拨号`() {
        val phoneId = "20000000-0000-4000-8000-000000000001"
        val tabletId = "21000000-0000-4000-8000-000000000002"
        val phoneMac = "6B:66:77:88:99:AA"
        val tabletMac = "6A:11:22:33:44:55"

        fun legacy(localId: String, remoteAddress: String) =
            (localId + "|" + remoteAddress).compareTo(remoteAddress + "|" + localId) > 0

        assertFalse("手机旧规则不拨号", legacy(phoneId, tabletMac))
        assertFalse("平板旧规则不拨号", legacy(tabletId, phoneMac))

        // 新规则：标识较大的一方拨号，另一方等待
        assertFalse(aDials(phoneId, tabletId, tabletMac))
        assertTrue(bDials(phoneId, tabletId, phoneMac))
    }

    @Test
    fun `标识取 deviceId 前缀且长度受限`() {
        assertEquals("200000", token("20000000-0000-4000-8000-000000000001"))
        assertEquals(6, token(UUID.randomUUID().toString()).length)
        // 广播包里给标识留的字节数有限，派生结果不能超长
        assertTrue(token("ABCDEF01-9999-4000-8000-000000000000").length <= 6)
    }

    @Test
    fun `对端没有广播标识时退回旧规则`() {
        val idA = "6c9f1a20-1111-4000-8000-000000000001"
        val idB = "3b7d4e10-2222-4000-8000-000000000002"
        val macA = "6B:66:77:88:99:AA"
        val macB = "6A:11:22:33:44:55"
        val aDials = MeshManager.dialDecision(token(idA), null, idA, macB)
        val bDials = MeshManager.dialDecision(token(idB), null, idB, macA)
        assertNotEquals("兜底规则同样不能两边都拨或都不拨", aDials, bDials)
    }

    private fun randomMac(random: Random): String =
        (0 until 6).joinToString(":") { "%02X".format(random.nextInt(256)) }
}
