import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                /// Talk 의 파일 대화 "AI 요약"이 aiagent://summarize?… 로 연다. 앱이 꺼져 있다가 링크로 켜질 때도 온다.
                .onOpenURL { url in
                    MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
                }
        }
    }
}
