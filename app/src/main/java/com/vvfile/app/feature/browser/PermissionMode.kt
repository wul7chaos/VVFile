package com.vvfile.app.feature.browser

/** 权限面板的三类主体。 */
enum class PermWho(val label: String) {
    OWNER("所有者"),
    GROUP("用户组"),
    OTHER("其它"),
}

/** 单个主体上的三个权限位。 */
enum class PermBit(val label: String, val mask: Int) {
    READ("读", 4),
    WRITE("写", 2),
    EXECUTE("执行", 1),
}

/** 特殊权限位：setuid / setgid / sticky。 */
enum class SpecialBit(val label: String, val mask: Int) {
    SET_UID("设置UID", 4),
    SET_GID("设置GID", 2),
    STICKY("粘滞", 1),
}

/**
 * POSIX 权限位：owner / group / other 各 0..7，外加特殊位 0..7（setuid=4、setgid=2、粘滞=1）。
 *
 * 权限面板的勾选、属性页显示的 `rw-r---`、以及 chmod 用的八进制串都从这里出，
 * 免得「界面显示的」和「实际下发的」对不上。
 */
data class PermissionMode(
    val owner: Int = 6,
    val group: Int = 4,
    val other: Int = 4,
    val special: Int = 0,
) {
    init {
        require(owner in 0..7 && group in 0..7 && other in 0..7 && special in 0..7) {
            "权限位必须在 0..7 之间"
        }
    }

    /** `660`；有特殊位时是 4 位（`4755`、`1777`），和 chmod 的写法一致。 */
    fun octal(): String = if (special == 0) "$owner$group$other" else "$special$owner$group$other"

    /** `rw-rw----`，setuid/setgid/sticky 按 `s`/`S`/`t`/`T` 规则还原。 */
    fun symbolic(): String =
        block(owner, uid = true) + block(group, gid = true) + block(other, sticky = true)

    fun enabled(who: PermWho, bit: PermBit): Boolean = (bitsOf(who) and bit.mask) != 0

    fun with(who: PermWho, bit: PermBit, value: Boolean): PermissionMode {
        val next = applyBit(bitsOf(who), bit.mask, value)
        return when (who) {
            PermWho.OWNER -> copy(owner = next)
            PermWho.GROUP -> copy(group = next)
            PermWho.OTHER -> copy(other = next)
        }
    }

    fun specialEnabled(bit: SpecialBit): Boolean = (special and bit.mask) != 0

    fun withSpecial(bit: SpecialBit, value: Boolean): PermissionMode =
        copy(special = applyBit(special, bit.mask, value))

    private fun bitsOf(who: PermWho): Int = when (who) {
        PermWho.OWNER -> owner
        PermWho.GROUP -> group
        PermWho.OTHER -> other
    }

    private fun block(bits: Int, uid: Boolean = false, gid: Boolean = false, sticky: Boolean = false): String {
        val read = bits and 4 != 0
        val write = bits and 2 != 0
        val execute = bits and 1 != 0
        val specialSet = when {
            uid -> special and 4 != 0
            gid -> special and 2 != 0
            sticky -> special and 1 != 0
            else -> false
        }
        val executeChar = when {
            // 有特殊位且可执行：s/t；没有执行位却设了特殊位：S/T（chmod 的写法就是这样）
            specialSet && execute -> if (sticky) 't' else 's'
            specialSet -> if (sticky) 'T' else 'S'
            execute -> 'x'
            else -> '-'
        }
        return buildString(3) {
            append(if (read) 'r' else '-')
            append(if (write) 'w' else '-')
            append(executeChar)
        }
    }

    companion object {
        val DEFAULT = PermissionMode()

        private const val SYMBOL_CHARS = "rwxsStT-"

        /** 解析 `rw-rw----`；长度不是 9 或含未知字符（例如读不到属性给的是 `—`）时返回 null。 */
        fun fromSymbolic(text: String): PermissionMode? {
            if (text.length != 9) return null
            if (text.any { it !in SYMBOL_CHARS }) return null
            var special = 0

            fun bits(offset: Int, specialMask: Int): Int {
                var value = 0
                if (text[offset] == 'r') value = value or 4
                if (text[offset + 1] == 'w') value = value or 2
                when (text[offset + 2]) {
                    'x' -> value = value or 1
                    's', 't' -> {
                        value = value or 1
                        special = special or specialMask
                    }

                    'S', 'T' -> special = special or specialMask
                }
                return value
            }

            val owner = bits(0, 4)
            val group = bits(3, 2)
            val other = bits(6, 1)
            return PermissionMode(owner, group, other, special)
        }

        /** 解析 `660` / `4755`；其它一律 null。 */
        fun fromOctal(text: String): PermissionMode? {
            val trimmed = text.trim()
            if (trimmed.length != 3 && trimmed.length != 4) return null
            if (trimmed.any { it !in '0'..'7' }) return null
            fun digit(index: Int) = trimmed[index] - '0'
            return if (trimmed.length == 3) {
                PermissionMode(digit(0), digit(1), digit(2))
            } else {
                PermissionMode(digit(1), digit(2), digit(3), digit(0))
            }
        }

        private fun applyBit(current: Int, mask: Int, value: Boolean): Int =
            if (value) current or mask else current and mask.inv()
    }
}
