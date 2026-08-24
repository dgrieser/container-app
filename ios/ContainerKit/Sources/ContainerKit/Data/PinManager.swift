import Foundation

/// Sets and verifies the admin PIN. The port of `PinManager.kt`.
public final class PinManager {

    private let store: any PinStore

    public init(store: any PinStore) {
        self.store = store
    }

    public var isPinSet: Bool {
        guard let hash = store.hash, !hash.isEmpty, let salt = store.salt, !salt.isEmpty else {
            return false
        }
        return true
    }

    public func setPin(_ pin: String) {
        let salt = PinHasher.makeSalt()
        store.salt = salt
        store.hash = PinHasher.hash(pin: pin, salt: salt)
    }

    public func verify(_ pin: String) -> Bool {
        guard let salt = store.salt, let expected = store.hash else { return false }
        return PinHasher.matches(PinHasher.hash(pin: pin, salt: salt), expected)
    }

    /// Whether a PIN is long enough to be set. Checked on create and change
    /// only, so an existing shorter PIN still opens the menu.
    public static func isLongEnough(_ pin: String) -> Bool {
        pin.count >= PinHasher.minimumLength
    }
}
