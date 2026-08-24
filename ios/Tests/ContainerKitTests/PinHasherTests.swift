import XCTest

@testable import ContainerKit

/// The PIN hashing, pinned to a golden vector.
///
/// This is the one place where matching Android exactly is not a nicety: the
/// scheme is a deliberately faithful port, quirks included, and a "tidier"
/// implementation would refuse the PIN of anyone who had already set one — on
/// the very screen whose job is to let them into the settings.
final class PinHasherTests: XCTestCase {

    /// Salt bytes 0x00…0x0F with the PIN "1234", as produced by
    /// `PinManager.hash` on the Android side. Verified against a run of that
    /// exact code, not merely against this implementation.
    func testTheGoldenVector() {
        let salt = Data((0 ..< 16).map(UInt8.init))
        XCTAssertEqual(
            "xvXlht3VXOaDR4ehlWDB9yvn4ExN/GvvhWRRsJC3bYU=",
            PinHasher.hash(pin: "1234", salt: salt)
        )
    }

    func testTheParametersMatchAndroid() {
        XCTAssertEqual(10_000, PinHasher.iterations)
        XCTAssertEqual(4, PinHasher.minimumLength)
        XCTAssertEqual(16, PinHasher.saltLength)
    }

    func testADifferentSaltGivesADifferentHash() {
        let a = PinHasher.hash(pin: "1234", salt: Data(repeating: 1, count: 16))
        let b = PinHasher.hash(pin: "1234", salt: Data(repeating: 2, count: 16))
        XCTAssertNotEqual(a, b)
    }

    func testTheSaltIsSixteenRandomBytes() {
        let first = PinHasher.makeSalt()
        XCTAssertEqual(PinHasher.saltLength, first.count)
        // Not a randomness test -- just that it is not a constant, which is the
        // mistake that would make every install share a salt.
        XCTAssertNotEqual(first, PinHasher.makeSalt())
    }

    func testComparisonIsExact() {
        let hash = PinHasher.hash(pin: "1234", salt: Data(repeating: 7, count: 16))
        XCTAssertTrue(PinHasher.matches(hash, hash))
        XCTAssertFalse(PinHasher.matches(hash, String(hash.dropLast())))
        XCTAssertFalse(PinHasher.matches("", hash))
    }

    func testSetAndVerifyRoundTrip() {
        let store = InMemoryPinStore()
        let manager = PinManager(store: store)
        XCTAssertFalse(manager.isPinSet)

        manager.setPin("4321")
        XCTAssertTrue(manager.isPinSet)
        XCTAssertTrue(manager.verify("4321"))
        XCTAssertFalse(manager.verify("1234"))
        XCTAssertFalse(manager.verify(""))
        // The PIN itself is never stored.
        XCTAssertNotEqual("4321", store.hash)
    }

    func testChangingThePinReplacesTheSalt() {
        let store = InMemoryPinStore()
        let manager = PinManager(store: store)
        manager.setPin("1111")
        let firstSalt = store.salt
        manager.setPin("2222")
        XCTAssertNotEqual(firstSalt, store.salt)
        XCTAssertFalse(manager.verify("1111"))
        XCTAssertTrue(manager.verify("2222"))
    }

    func testAHalfWrittenStoreCountsAsNoPin() {
        // A hash with no salt cannot be verified against anything, so treating it
        // as "set" would lock the admin menu for good.
        XCTAssertFalse(PinManager(store: InMemoryPinStore(salt: Data(repeating: 1, count: 16))).isPinSet)
        XCTAssertFalse(PinManager(store: InMemoryPinStore(hash: "abc")).isPinSet)
    }

    func testMinimumLengthIsCheckedOnlyWhereAndroidChecksIt() {
        XCTAssertFalse(PinManager.isLongEnough("123"))
        XCTAssertTrue(PinManager.isLongEnough("1234"))

        // An existing shorter PIN still opens the menu: the length rule applies to
        // setting and changing, not to verifying.
        let manager = PinManager(store: InMemoryPinStore())
        manager.setPin("12")
        XCTAssertTrue(manager.verify("12"))
    }
}
