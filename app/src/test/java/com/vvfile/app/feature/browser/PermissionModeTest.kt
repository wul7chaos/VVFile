package com.vvfile.app.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionModeTest {

    @Test
    fun `默认是 644`() {
        assertEquals("644", PermissionMode.DEFAULT.octal())
        assertEquals("rw-r--r--", PermissionMode.DEFAULT.symbolic())
    }

    @Test
    fun `符号串与八进制互相还原`() {
        val mode = PermissionMode.fromSymbolic("rw-rw----")
        assertEquals(PermissionMode(6, 6, 0), mode)
        assertEquals("660", mode?.octal())
        assertEquals("rw-rw----", mode?.symbolic())
    }

    @Test
    fun `带 setuid 时输出四位八进制和 s`() {
        val mode = PermissionMode.fromOctal("4755")
        assertEquals(PermissionMode(7, 5, 5, special = 4), mode)
        assertEquals("rwsr-xr-x", mode?.symbolic())
        assertEquals("4755", mode?.octal())
    }

    @Test
    fun `粘滞位在其它执行位上显示 t`() {
        val mode = PermissionMode.fromOctal("1777")
        assertEquals("rwxrwxrwt", mode?.symbolic())
        assertEquals("1777", mode?.octal())
    }

    @Test
    fun `设了特殊位但没有执行位时用大写 S T`() {
        assertEquals("rwSr--r--", PermissionMode(6, 4, 4, special = 4).symbolic())
        assertEquals("rw-rw-r-T", PermissionMode(6, 6, 4, special = 1).symbolic())
    }

    @Test
    fun `setgid 落在用户组的执行位`() {
        assertEquals("rw-r-sr--", PermissionMode(6, 5, 4, special = 2).symbolic())
    }

    @Test
    fun `无法读取属性时返回 null`() {
        assertNull(PermissionMode.fromSymbolic("—"))
        assertNull(PermissionMode.fromSymbolic(""))
        assertNull(PermissionMode.fromSymbolic("rw-rw--"))
        assertNull(PermissionMode.fromSymbolic("rw-rw--x?"))
    }

    @Test
    fun `八进制只接受 3 位或 4 位的 0 到 7`() {
        assertNull(PermissionMode.fromOctal("66"))
        assertNull(PermissionMode.fromOctal("77777"))
        assertNull(PermissionMode.fromOctal("888"))
        assertEquals(PermissionMode(7, 7, 7), PermissionMode.fromOctal("777"))
        assertEquals(PermissionMode(6, 6, 6, 6), PermissionMode.fromOctal("6666"))
        assertEquals(PermissionMode(7, 7, 7, 4), PermissionMode.fromOctal(" 4777 "))
    }

    @Test
    fun `勾选与取消勾选只改对应的位`() {
        val mode = PermissionMode.DEFAULT
        val writable = mode.with(PermWho.GROUP, PermBit.WRITE, true)
        assertTrue(writable.enabled(PermWho.GROUP, PermBit.WRITE))
        assertEquals("664", writable.octal())

        val back = writable.with(PermWho.GROUP, PermBit.WRITE, false)
        assertEquals("644", back.octal())

        val executable = mode.with(PermWho.OWNER, PermBit.EXECUTE, true)
        assertEquals("744", executable.octal())
    }

    @Test
    fun `特殊位可以单独开关`() {
        val mode = PermissionMode(7, 5, 5)
        val setuid = mode.withSpecial(SpecialBit.SET_UID, true)
        assertEquals("4755", setuid.octal())
        assertTrue(setuid.specialEnabled(SpecialBit.SET_UID))
        assertFalse(setuid.specialEnabled(SpecialBit.SET_GID))
        assertEquals("755", setuid.withSpecial(SpecialBit.SET_UID, false).octal())
    }

    @Test
    fun `全开全关`() {
        val all = PermissionMode(7, 7, 7, special = 7)
        assertEquals("7777", all.octal())
        assertEquals("rwsrwsrwt", all.symbolic())
        assertEquals(PermissionMode(0, 0, 0, 0), PermissionMode.fromSymbolic("---------"))
    }

    @Test
    fun `脚本面板用的三位权限位`() {
        // ScriptRunner 只认三位，所以脚本面板取 takeLast(3)
        assertEquals("755", PermissionMode(7, 5, 5, 4).octal().takeLast(3))
    }
}
