import Foundation
import Security
import StratoKit

/// Kotlin SecureStore over the Keychain: generic passwords, this device only, readable after the
/// first unlock (so background work can reach them), excluded from backups.
final class KeychainStore: NSObject, SecureStore {

    private let service = "app.strato.ios"

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    func read(key: String) -> Data? {
        var request = query(key)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    func write(key: String, value: Data) -> Bool {
        SecItemDelete(query(key) as CFDictionary)
        var item = query(key)
        item[kSecValueData as String] = value
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }

    func remove(key: String) {
        SecItemDelete(query(key) as CFDictionary)
    }
}
