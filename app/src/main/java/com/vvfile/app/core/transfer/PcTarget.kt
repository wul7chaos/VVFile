package com.vvfile.app.core.transfer

/**
 * 电脑端接收器的网络位置，由局域网发现（[PcDiscovery]）得到，不需要用户手填。
 */
data class PcTarget(val host: String, val port: Int) {

    /** 显示与日志用的 `host:port`。 */
    val authority: String get() = "$host:$port"

    companion object {
        /** 电脑端默认端口，与 pc/VvFileReceiver 一致。 */
        const val DEFAULT_PORT: Int = 7717
    }
}

/** 一次收发的结论；[message] 直接可展示。 */
data class PcResult(
    val ok: Boolean,
    val message: String,
    /** 电脑端回报的落地路径（只有上传成功时才有）。 */
    val savedPath: String? = null,
)
