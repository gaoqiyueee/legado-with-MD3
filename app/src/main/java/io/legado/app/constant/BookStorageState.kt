package io.legado.app.constant

/**
 * 书籍本地存储状态。
 * 使用 Int 常量而非 enum，方便直接存储在 Room 数据库字段中，无需 TypeConverter。
 */
object BookStorageState {
    /** 本地文件存在，可正常阅读（默认状态） */
    const val LOCAL = 0

    /** 仅有元信息，无本地文件（从 WebDAV 扫描创建），需下载后才能阅读 */
    const val METADATA_ONLY = 1

    /** 曾在本地，文件已删除归档到 WebDAV，需重新下载才能阅读 */
    const val ARCHIVED = 2
}
