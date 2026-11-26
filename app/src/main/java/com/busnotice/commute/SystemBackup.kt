package com.busnotice.commute

import android.app.backup.BackupManager
import android.content.Context

/**
 * 是否参与系统备份（手机云备份、换机迁移），默认开启，只存本机。
 *
 * - 参与备份的数据由 res/xml/data_extraction_rules.xml 列出（白名单）：行程、数据源设置、学习结果、设置检查勾选与引导进度；
 * - 原始定位采样、行程记录（轨迹、基站、传感器）、基站指纹、行程运行状态与各类缓存不在名单内，任何情况下都不备份；
 * - 开关在 [CommuteBackupAgent] 中读取：关闭时备份内容为空。
 */
object SystemBackup {
    const val PREFS = "backup"
    private const val KEY_ENABLED = "enabled"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        // 通知系统备份内容有变化，下一次备份按新设置执行
        runCatching { BackupManager(ctx).dataChanged() }
    }
}
