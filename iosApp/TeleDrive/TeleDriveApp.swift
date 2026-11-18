import SwiftUI
import TeleDriveKit

@main
struct TeleDriveApp: App {
    /// One bridge for the app's lifetime: TDLib clients and the receive loop live behind it.
    private let tdlib = TdJsonBridge()

    init() {
        // Must precede any Kotlin code that encrypts or decrypts.
        IosCryptoBridge.shared.aesGcm = CryptoKitAesGcm()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView(transport: tdlib)
                .ignoresSafeArea()
        }
    }
}

/// Hosts the Compose Multiplatform UI exported by the Kotlin framework.
struct ComposeView: UIViewControllerRepresentable {
    let transport: TdJsonTransport

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(transport: transport)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
