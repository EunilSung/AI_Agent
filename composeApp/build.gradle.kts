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
 * 앱의 설정 화면에서 바꾼 주소가 있으면 그것이 먼저다. 환경변수 `AI_AGENT_BRIDGE_URL` 이 있으면 `local.properties` 보다 먼저다.
 */
val bridgeBaseUrlProperty: String = providers.environmentVariable("AI_AGENT_BRIDGE_URL").orNull?.trim()
    ?: localProperties.getProperty("bridge.baseUrl").orEmpty().trim()

/**
 * configuration cache 가 켜져 있어 빌드 스크립트 객체를 붙잡는 doLast 람다는 쓸 수 없다.
 * 입력과 출력을 프로퍼티로 선언한 타입 태스크로 만든다. Talk 의 `GenerateServerConfigTask` 와 같은 방식이다.
 */
abstract class GenerateBridgeConfigTask : DefaultTask() {
    @get:Input
    abstract val bridgeBaseUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = outputDir.get().asFile.resolve("com/eunilsung/aiagent/data/remote")
        directory.mkdirs()
        directory.resolve("BridgeBuildConfig.kt").writeText(
            """
            package com.eunilsung.aiagent.data.remote

            /** local.properties 의 bridge.baseUrl(또는 AI_AGENT_BRIDGE_URL)에서 빌드 시점에 생성됨. 직접 수정하지 말 것. */
            internal const val BRIDGE_BASE_URL: String = "${bridgeBaseUrl.get()}"
            """.trimIndent() + "\n"
        )
    }
}

val generateBridgeConfig by tasks.registering(GenerateBridgeConfigTask::class) {
    bridgeBaseUrl.set(bridgeBaseUrlProperty)
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

android {
    namespace = "com.eunilsung.aiagent"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.eunilsung.aiagent"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
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
