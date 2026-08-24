import Foundation
import Security

/// Where the PIN's salt and hash live.
///
/// The keychain, not `UserDefaults`, which is the one place this port
/// deliberately differs from Android's `SharedPreferences`: on iOS the keychain
/// is the only store that is encrypted at rest and not swept up by a device
/// backup of app preferences.
///
/// `…WhenUnlockedThisDeviceOnly` on both items, for two reasons: nothing here is
/// needed while the device is locked, and an admin PIN should not travel to
/// another device in an iCloud restore.
///
/// One consequence worth knowing, because it differs from Android: keychain
/// items outlive app deletion. Reinstalling keeps the PIN.
public protocol PinStore: AnyObject {
    var salt: Data? { get set }
    var hash: String? { get set }
}

public final class KeychainPinStore: PinStore {

    private enum Key: String {
        case salt = "pin_salt"
        case hash = "pin_hash"
    }

    private let service: String

    /// - Parameter service: the keychain service, scoped to the variant so two
    ///   installed variants keep separate PINs — the counterpart of their
    ///   separate `SharedPreferences` files.
    public init(service: String) {
        self.service = service
    }

    public var salt: Data? {
        get { read(.salt) }
        set { write(.salt, newValue) }
    }

    public var hash: String? {
        get { read(.hash).flatMap { String(data: $0, encoding: .utf8) } }
        set { write(.hash, newValue?.data(using: .utf8)) }
    }

    private func query(_ key: Key) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
    }

    private func read(_ key: Key) -> Data? {
        var request = query(key)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess else {
            return nil
        }
        return result as? Data
    }

    private func write(_ key: Key, _ value: Data?) {
        guard let value else {
            SecItemDelete(query(key) as CFDictionary)
            return
        }
        let attributes: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updated = SecItemUpdate(
            query(key) as CFDictionary,
            attributes as CFDictionary
        )
        if updated == errSecItemNotFound {
            var item = query(key)
            item.merge(attributes) { _, new in new }
            SecItemAdd(item as CFDictionary, nil)
        }
    }
}

/// A store that keeps the PIN in memory, for tests and for previews.
public final class InMemoryPinStore: PinStore {
    public var salt: Data?
    public var hash: String?
    public init(salt: Data? = nil, hash: String? = nil) {
        self.salt = salt
        self.hash = hash
    }
}
