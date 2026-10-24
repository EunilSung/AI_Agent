import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

/**
 * 브릿지 주소를 `local.properties` 의 `bridge.baseUrl` 에서 읽는다.
 *
 * 비워 두면 플랫폼 기본값을 쓴다 — Android 에뮬레이터는 `http://10.0.2.2:8095`, iOS 시뮬레이터는 `http://localhost:8095`.
 * 접속 키까지 비운 빌드(공개 릴리스)는 이 기본값으로 아무것도 보내지 않고, 로그인 화면에서 서버 주소를 먼저 받는다.
 * 앱의 설정 화면에서 바꾼 주소가 있으면 그것이 먼저다. 환경변수 `AI_AGENT_BRIDGE_URL` 이 있으면 `local.properties` 보다 먼저다.
 * https 주소이거나 위 로컬 주소의 http 만 쓸 수 있다 — 그 밖이면 [GenerateBridgeConfigTask] 가 빌드를 멈춘다. 실기기는 https 터널 주소로 빌드한다.
 */
val bridgeBaseUrlProperty: String = providers.environmentVariable("AI_AGENT_BRIDGE_URL").orNull?.trim()
    ?: localProperties.getProperty("bridge.baseUrl").orEmpty().trim()

/**
 * 브릿지 접속 키를 `local.properties` 의 `bridge.accessKey` 에서 읽는다. 환경변수 `AI_AGENT_BRIDGE_KEY` 가 있으면 그것이 먼저다.
 *
 * 앱은 모든 브릿지 요청의 `X-Bridge-Key` 헤더에 이 값을 싣고, 브릿지는 같은 값(브릿지의 `BRIDGE_ACCESS_KEY`)일 때만 받는다.
 * 공개 저장소라 값은 저장소에 두지 않는다 — `local.properties` 는 git 에 올라가지 않고, 생성 파일은 `build/` 안에 생긴다.
 * 개발용 빌드에서만 넣는다. 키는 앱 바이너리(APK·IPA)에 그대로 들어가 꺼낼 수 있어, 넣은 빌드는 바깥 사람에게 주지 않고
 * 새어 나갔으면 브릿지의 `BRIDGE_ACCESS_KEY` 와 함께 바꾼다. 앱은 이 키를 빌드 기본 주소의 호스트와 로컬 주소에만 싣는다.
 * 비워 두면(공개 릴리스) 쓰는 사람이 로그인 화면에서 받은 키를 넣고, 앱은 그 키를 넣을 때의 서버 출처(스킴·호스트·포트)에만
 * 싣는다(`AccessKeyStore`).
 */
val bridgeAccessKeyProperty: String = providers.environmentVariable("AI_AGENT_BRIDGE_KEY").orNull?.trim()
    ?: localProperties.getProperty("bridge.accessKey").orEmpty().trim()

/**
 * configuration cache 가 켜져 있어 빌드 스크립트 객체를 붙잡는 doLast 람다는 쓸 수 없다.
 * 입력과 출력을 프로퍼티로 선언한 타입 태스크로 만든다. Talk 의 `GenerateServerConfigTask` 와 같은 방식이다.
 */
abstract class GenerateBridgeConfigTask : DefaultTask() {
    @get:Input
    abstract val bridgeBaseUrl: Property<String>

    @get:Input
    abstract val bridgeAccessKey: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val baseUrl = bridgeBaseUrl.get().trimEnd('/')
        if (baseUrl.isNotEmpty() && !isAllowedBridgeUrl(baseUrl)) {
            throw GradleException(
                "bridge.baseUrl(또는 AI_AGENT_BRIDGE_URL)은 https 주소이거나 http://10.0.2.2·localhost·127.0.0.1 이어야 한다. " +
                    "같은 망의 http 주소(<이름>.local·IP)로 빌드하면 iOS 실기기는 OS 가 막지 않아 비밀번호·토큰·대화를 평문으로 보낸다. " +
                    "실기기는 https 터널 주소로 빌드한다."
            )
        }
        if (bridgeAccessKey.get().isEmpty()) {
            logger.lifecycle("bridge.accessKey(또는 AI_AGENT_BRIDGE_KEY)가 비어 있다 — 이 빌드의 앱은 로그인 화면에서 접속 키를 받는다.")
        }
        val directory = outputDir.get().asFile.resolve("com/eunilsung/aiagent/data/remote")
        directory.mkdirs()
        directory.resolve("BridgeBuildConfig.kt").writeText(
            """
            package com.eunilsung.aiagent.data.remote

            /** local.properties 의 bridge.baseUrl(또는 AI_AGENT_BRIDGE_URL)에서 빌드 시점에 생성됨. 직접 수정하지 말 것. */
            internal const val BRIDGE_BASE_URL: String = "${kotlinLiteral(bridgeBaseUrl.get())}"

            /** local.properties 의 bridge.accessKey(또는 AI_AGENT_BRIDGE_KEY)에서 빌드 시점에 생성됨. 직접 수정하지 말 것. */
            internal const val BRIDGE_ACCESS_KEY: String = "${kotlinLiteral(bridgeAccessKey.get())}"
            """.trimIndent() + "\n"
        )
    }

    /**
     * 앱의 주소 규칙(`domain/util/BridgeAddress.kt`)과 같은 검사 — https 이거나, 평문이면 에뮬레이터·이 기기 주소만 받는다.
     * 사용자 정보(`@`)·질의(`?`)·조각(`#`)·공백이 든 주소와 범위 밖 포트도 받지 않는다. 앱도 실행할 때 같은 규칙으로 한 번 더
     * 거른다(맞지 않으면 플랫폼 기본값). 호스트 목록이 앱의 규칙과 같은지는 `PlatformNetworkPolicyTest` 가 본다.
     */
    private fun isAllowedBridgeUrl(url: String): Boolean {
        val bridgeCleartextHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")
        val pattern = Regex("([A-Za-z]+)://([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)(?::([0-9]{1,5}))?(/[^\\s\\p{Cntrl}@?#\\\\]*)?")
        val match = pattern.matchEntire(url) ?: return false
        val (scheme, host, port) = match.destructured
        if (port.isNotEmpty() && port.toInt() !in 1..65_535) return false
        return when (scheme.lowercase()) {
            "https" -> true
            "http" -> host.lowercase() in bridgeCleartextHosts
            else -> false
        }
    }

    /** 큰따옴표 문자열 안에 그대로 넣을 수 있게 바꾼다. 줄바꿈이 든 값은 설정 실수라 멈춘다. */
    private fun kotlinLiteral(value: String): String {
        require(value.none { it == '\n' || it == '\r' }) { "브릿지 설정 값에 줄바꿈이 있다" }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
    }
}

val generateBridgeConfig by tasks.registering(GenerateBridgeConfigTask::class) {
    bridgeBaseUrl.set(bridgeBaseUrlProperty)
    bridgeAccessKey.set(bridgeAccessKeyProperty)
    outputDir.set(layout.buildDirectory.dir("generated/bridgeConfig/commonMain/kotlin"))
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.ktor.client.okhttp)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.android)
        }
        commonMain {
            kotlin.srcDir(generateBridgeConfig)
        }
        commonMain.dependencies {
            implementation(projects.shared)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)

            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.no.arg)

            implementation(libs.voyager.navigator)

            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.ktor.client.mock)
        }
        androidUnitTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.ktor.client.darwin)
        }
        iosTest.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.eunilsung.aiagent.db")
        }
    }
}

compose.resources {
    packageOfResClass = "aiagent.composeapp.generated.resources"
}

/**
 * 릴리스 APK 서명 키 파일. GitHub Actions 의 릴리스 작업이 `release` 환경 비밀값을 임시 파일로 풀어
 * 환경변수 `ANDROID_KEYSTORE_FILE`(+ `ANDROID_KEYSTORE_PASSWORD`·`ANDROID_KEY_ALIAS`·`ANDROID_KEY_PASSWORD`)로 넘긴다.
 * 값이 없으면 release 빌드는 서명하지 않는다. 키 파일과 비밀번호는 저장소에 두지 않는다.
 */
val releaseKeystoreFile: String? = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.eunilsung.aiagent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.eunilsung.aiagent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

/** 도메인 모델은 Compose 를 모른다. 바뀌지 않는 값이라는 사실을 설정 파일로 알려 화면 건너뛰기를 유지한다. */
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}
