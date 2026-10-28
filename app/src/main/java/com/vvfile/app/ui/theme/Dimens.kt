package com.vvfile.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 贴底栏的通用尺寸。
 *
 * 全面屏手势条藏起来之后，系统栏 inset 变成 0，底部内容会直接顶到屏幕边缘；
 * 圆角屏的圆角是切在窗口上的，inset 里并不包含圆角半径，
 * 于是最后一两行字正好落在圆角弧线里被切掉。这里统一给底部栏留出这点余量。
 */
object VVDimens {
    /** 底部栏左右内边距：让开圆角弧线。 */
    val BottomBarHorizontal = 20.dp

    /** 底部栏离屏幕底边的余量：手势条隐藏后自己补的一点安全距离。 */
    val BottomBarGap = 8.dp
}
