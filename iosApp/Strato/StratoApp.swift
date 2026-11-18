import SwiftUI
import StratoKit

@main
struct StratoApp: App {
    /// One bridge for the app's lifetime: TDLib clients and the receive loop live behind it.
    private let tdlib = TdJsonBridge()
    private let keychain = KeychainStore()

    init() {
        // Must precede any Kotlin code that encrypts or decrypts.
        IosCryptoBridge.shared.aesGcm = CryptoKitAesGcm()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView(transport: tdlib, secureStore: keychain)
                .ignoresSafeArea()
        }
    }
}

/// Hosts the Compose Multiplatform UI exported by the Kotlin framework.
struct ComposeView: UIViewControllerRepresentable {
    let transport: TdJsonTransport
    let secureStore: SecureStore

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(transport: transport, secureStore: secureStore)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
