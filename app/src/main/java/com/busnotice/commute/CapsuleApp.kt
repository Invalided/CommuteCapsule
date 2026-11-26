package com.busnotice.commute

import android.app.Application

class CapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Transit.init(this)
        // 加载本机加密密钥，并把旧版本的明文原始定位与行程记录转为密文
        Thread({
            runCatching { LocalCrypto.warm(this) }
            runCatching { TraceStore.migrateAll(this) }
            runCatching { Places.encryptStoredSamples(this) }
        }, "local-crypto").start()
    }
}
