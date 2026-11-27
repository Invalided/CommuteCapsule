package com.busnotice.commute

import android.app.Application

class CapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Transit.init(this)
        // 加载本机加密密钥，把旧版本的明文原始定位与行程记录转为密文，并把此前日期的行程记录整理为压缩块
        Thread({
            runCatching { LocalCrypto.warm(this) }
            runCatching { TraceStore.migrateAll(this) }
            runCatching { Places.encryptStoredSamples(this) }
            runCatching { TraceStore.compactAll(this) }
            runCatching { TraceStore.refreshLocked(this) }
            runCatching { MigrationStore.expireUndo(this) }
        }, "local-crypto").start()
    }
}
