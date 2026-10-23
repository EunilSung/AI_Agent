package com.eunilsung.aiagent

import android.app.Application
import com.eunilsung.aiagent.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApplication)
            modules(appModule, platformModule)
        }
    }
}
