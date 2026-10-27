package com.vvfile.app.core.archive

import java.io.IOException

/**
 * 压缩包密码相关的异常。
 *
 * 单独定义类型，是为了让 UI 分辨「要密码」和「密码错」：前者弹输入框，
 * 后者留在弹窗上说一句「密码不正确」，而不是把 `invalid entry CRC` 这种
 * 底层信息摔到用户脸上。
 */
open class ArchivePasswordException(message: String) : IOException(message)

/** 条目/包头带密码，调用方还没给密码。 */
class ArchivePasswordRequiredException(message: String = "压缩包需要密码") :
    ArchivePasswordException(message)

/** 给了密码但校验不通过。 */
class ArchivePasswordWrongException(message: String = "密码不正确") :
    ArchivePasswordException(message)

/** 认得是加密，但算法不支持（例如 PPMd 或未知的 AES 强度）。 */
class ArchiveEncryptionUnsupportedException(message: String) : IOException(message)
