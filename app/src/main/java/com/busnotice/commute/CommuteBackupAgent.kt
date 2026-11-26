package com.busnotice.commute

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * 系统备份代理（Manifest 中 fullBackupOnly = true，只走完整备份）。
 *
 * 云备份与换机迁移（设备间传输）都经由 [onFullBackup]：
 * - 「参与系统备份」关闭时不写入任何内容；
 * - 开启时交给系统默认实现，按 data_extraction_rules.xml 的白名单备份。
 * 备份前先把旧版本混存在学习结果文件里的原始定位采样搬到单独文件，确保它不随学习结果一起备份。
 */
class CommuteBackupAgent : BackupAgent() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        if (!SystemBackup.enabled(this)) return
        runCatching { Places.migrateSamples(this) }
        super.onFullBackup(data)
    }

    // 键值备份不使用（fullBackupOnly）
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}
}
