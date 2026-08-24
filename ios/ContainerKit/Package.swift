// swift-tools-version:5.9
import PackageDescription

// All of the app's runtime code, as a local package rather than as sources of
// each variant target.
//
// Two reasons, both practical. SwiftPM globs its sources at *build* time where
// XcodeGen globs at *generation* time, so adding a Swift file here needs no
// `make project` — only adding a variant does, which is what makes a generated
// project bearable day to day. And the pure logic (the domain lock, the config
// parsing, the PIN hashing) can then be tested with `swift test`, without Xcode
// and without a simulator.
let package = Package(
    name: "ContainerKit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "ContainerKit", targets: ["ContainerKit"])
    ],
    targets: [
        .target(
            name: "ContainerKit",
            path: "Sources/ContainerKit",
            swiftSettings: [.enableUpcomingFeature("ExistentialAny")]
        ),
        .testTarget(
            name: "ContainerKitTests",
            dependencies: ["ContainerKit"],
            path: "Tests/ContainerKitTests"
        ),
    ]
)
