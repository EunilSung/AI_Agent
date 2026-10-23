import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 브릿지만 빌드할 때는 안드로이드 타깃을 뺀다 — 그 환경에는 Android SDK 가 없다.
 * 이유는 `settings.gradle.kts` 의 같은 이름 변수에 적어 두었다.
 */
val bridgeOnly = System.getenv("BRIDGE_ONLY") == "1"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinSerialization)
}

/** KMP 의 androidTarget 은 이 플러그인이 먼저 적용돼 있어야 선언할 수 있다. */
if (!bridgeOnly) apply(plugin = libs.plugins.androidLibrary.get().pluginId)

/**
 * 앱과 브릿지가 함께 쓰는 모듈 — 요청·응답 모델.
 *
 * 여기 있는 타입이 브릿지와 앱 사이의 계약이다. 한쪽만 고치면 컴파일이 깨지므로 응답 모양이
 * 조용히 어긋날 수 없다. 브릿지가 JVM 이라 jvm 타깃이 함께 필요하다.
 */
kotlin {
    jvm()
    if (!bridgeOnly) {
        androidTarget {
            compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}

/** `android { }` 접근자는 플러그인이 정적으로 적용돼야 생긴다. 조건부 적용이라 타입으로 직접 설정한다. */
if (!bridgeOnly) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.eunilsung.aiagent.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
