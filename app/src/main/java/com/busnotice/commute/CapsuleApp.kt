package com.busnotice.commute

import android.app.Application

class CapsuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Transit.init(this)
    }
}
