package com.eunilsung.aiagent

import android.app.Application
import com.eunilsung.aiagent.data.local.AndroidLocalSecretSetup
import com.eunilsung.aiagent.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        /** 로그인 정보 저장소가 시작하자마자 잠그고 풀므로 Koin 보다 먼저 데이터 키 위치를 알려 준다. Talk 와 같은 순서다. */
        AndroidLocalSecretSetup.init(this)

        startKoin {
            androidContext(this@MyApplication)
            modules(appModule, platformModule)
        }
    }
}
