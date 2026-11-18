import CryptoKit
import Foundation
import TeleDriveKit

/// AES-256-GCM for the Kotlin encryption formats. CryptoKit's sealed box splits ciphertext and the
/// 16-byte tag; javax.crypto (Android) concatenates them, so this provider does the same.
final class CryptoKitAesGcm: NSObject, AesGcmProvider {

    private static let tagLength = 16

    func seal(key: Data, nonce: Data, aad: Data?, plaintext: Data) -> Data? {
        do {
            let box = try AES.GCM.seal(
                plaintext,
                using: SymmetricKey(data: key),
                nonce: try AES.GCM.Nonce(data: nonce),
                authenticating: aad ?? Data()
            )
            return box.ciphertext + box.tag
        } catch {
            return nil
        }
    }

    func open(key: Data, nonce: Data, aad: Data?, sealed: Data) -> Data? {
        guard sealed.count >= Self.tagLength else { return nil }
        do {
            let box = try AES.GCM.SealedBox(
                nonce: try AES.GCM.Nonce(data: nonce),
                ciphertext: sealed.prefix(sealed.count - Self.tagLength),
                tag: sealed.suffix(Self.tagLength)
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad ?? Data())
        } catch {
            return nil
        }
    }
}
