// swift-tools-version:5.9
import PackageDescription

// All of the app's runtime code, as a local package rather than as sources of
// each variant target.
//
// The reason is practical: SwiftPM globs its sources at *build* time where
// XcodeGen globs at *generation* time, so adding a Swift file here needs no
// `make project` — only adding a variant does, which is what makes a generated
// project bearable day to day.
//
// The tests are deliberately *not* here. This is a UIKit and WebKit package, so
// `swift test` cannot build it on any host — the tests are an Xcode unit-test
// target (../Tests/ContainerKitTests, declared in ../project.yml) run against an
// iOS simulator.
let package = Package(
    name: "ContainerKit",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "ContainerKit", targets: ["ContainerKit"])
    ],
    targets: [
        .target(name: "ContainerKit", path: "Sources/ContainerKit")
    ]
)
