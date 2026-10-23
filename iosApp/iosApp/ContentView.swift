import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .background(themeBackground.ignoresSafeArea())
    }

    /// AppColors.Bg 와 같다 — 다크: #121417, 라이트: #FFFFFF. Compose 가 칠하지 않는 안전 영역을 테마 배경으로 채운다.
    private var themeBackground: Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0x12 / 255.0, green: 0x14 / 255.0, blue: 0x17 / 255.0, alpha: 1.0)
                : UIColor.white
        })
    }
}
