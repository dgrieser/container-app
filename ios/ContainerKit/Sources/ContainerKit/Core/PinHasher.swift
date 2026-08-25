import CryptoKit
import Foundation

/// How the admin PIN is stored: a random per-install salt and a salted, iterated
/// SHA-256 digest, never the PIN itself.
///
/// This is a **deliberately exact** port of `PinManager.kt`, iteration quirk
/// included: each round digests the previous 32-byte digest without re-mixing
/// the salt or the PIN. That is weaker than PBKDF2 would be, and it is kept
/// because the alternative is worse — a "better" scheme here would reject the
/// PIN of anyone who had already set one, on a screen whose whole purpose is to
/// let them into the settings. It is a UI gate on the admin menu, not a
/// cryptographic secret store, and the hash lives in the keychain rather than in
/// `UserDefaults`.
///
/// `PinHasherTests` pins a golden vector, so this cannot drift by accident.
public enum PinHasher {

    /// Rounds of SHA-256. The same count as Android's, because the digest has to
    /// come out the same.
    public static let iterations = 10_000

    /// Shortest PIN the setup and change flows accept.
    public static let minimumLength = 4

    /// Bytes of salt, matching Android's `ByteArray(16)`.
    public static let saltLength = 16

    public static func makeSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: saltLength)
        // SystemRandomNumberGenerator is seeded by the system CSPRNG, the
        // counterpart of SecureRandom.
        var generator = SystemRandomNumberGenerator()
        for index in bytes.indices {
            bytes[index] = UInt8.random(in: .min ... .max, using: &generator)
        }
        return Data(bytes)
    }

    /// The stored digest for `pin` under `salt`, Base64 with no line breaks.
    public static func hash(pin: String, salt: Data) -> String {
        var data = salt + Data(pin.utf8)
        for _ in 0 ..< iterations {
            data = Data(SHA256.hash(data: data))
        }
        return data.base64EncodedString()
    }

    /// Compares two stored digests without leaking where they differ.
    ///
    /// Both are Base64 of a 32-byte digest, so their lengths match unless the
    /// stored value is corrupt — which is itself worth answering "no" to.
    public static func matches(_ candidate: String, _ expected: String) -> Bool {
        let a = Array(candidate.utf8)
        let b = Array(expected.utf8)
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for index in a.indices {
            difference |= a[index] ^ b[index]
        }
        return difference == 0
    }
}
