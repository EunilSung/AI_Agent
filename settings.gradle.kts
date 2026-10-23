rootProject.name = "AIAgent"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

/**
 * 브릿지만 빌드하는가 — 배포본을 만들 때 켠다.
 *
 * 앱 모듈은 Android SDK 가 있어야 설정 단계부터 통과한다. SDK 가 없는 곳에서 브릿지만 빌드할 수 있도록
 * 안드로이드 쪽을 통째로 뺀다. Talk 의 `SERVER_ONLY` 와 같은 방식이다.
 */
val bridgeOnly = System.getenv("BRIDGE_ONLY") == "1"

if (!bridgeOnly) include(":composeApp")

/** 앱과 브릿지가 요청·응답 모델을 공유한다. 빌드를 쪼개면 공유가 안 되므로 한 빌드에 둔다. */
include(":shared")
include(":bridge")
