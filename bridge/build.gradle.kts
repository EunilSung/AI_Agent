import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.eunilsung.aiagent.bridge.ApplicationKt")
}

dependencies {
    implementation(projects.shared)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

/**
 * 브릿지 접속 키. 환경변수 `BRIDGE_ACCESS_KEY` 가 있으면 그것을, 없으면 `local.properties` 의 `bridge.accessKey` 를 쓴다.
 * 앱 빌드(`composeApp`)도 같은 값을 읽어 요청에 싣는다. 값은 공개 저장소에 두지 않는다 — `local.properties` 는 git 밖이다.
 */
val bridgeAccessKey: String = providers.environmentVariable("BRIDGE_ACCESS_KEY").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(text.reader()) }.getProperty("bridge.accessKey") }
        ?.trim()
        .orEmpty()

/**
 * `./gradlew :bridge:run` 이 이 PC 의 RAG 서버(8000)·Talk 로컬 서버(8090)에 붙어 `127.0.0.1:8095` 에서 뜬다.
 * 접속 키가 없으면 브릿지가 뜨지 않는다(이 기기 안에서만 시험할 때는 `BRIDGE_ALLOW_NO_KEY=1`).
 */
tasks.named<JavaExec>("run") {
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    if (bridgeAccessKey.isNotEmpty()) environment("BRIDGE_ACCESS_KEY", bridgeAccessKey)
}
