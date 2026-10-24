package com.vvfile.app.core.result

import java.io.FileNotFoundException
import java.io.IOException

/**
 * 文件系统领域异常。数据层只抛这些类型，UI 层据此转成用户可读文案。
 *
 * 继承关系刻意与 M0 的既有抛法保持一致，避免 UI 侧（`feature/browser`）的异常映射失效：
 * - [PathNotFound] 继承 `FileNotFoundException`（本就是 `IOException`），"目录不存在"文案不变；
 * - [PathAccessDenied] 继承 `SecurityException`，"没有权限读取此目录"文案不变；
 * - [NotADirectory] 继承 `IOException`。
 */
class PathNotFound(val path: String) : FileNotFoundException("路径不存在：$path")

class PathAccessDenied(val path: String) : SecurityException("没有权限访问：$path")

class NotADirectory(val path: String) : IOException("不是文件夹：$path")
