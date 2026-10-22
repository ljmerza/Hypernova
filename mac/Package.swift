// swift-tools-version:6.0
// Hypernova for Mac: a menu bar app (SwiftUI) that sets up and watches the Hypernova daemon.
// Built with SwiftPM only (no Xcode project); mac/build.sh assembles dist/Hypernova.app.
import PackageDescription

let package = Package(
    name: "Hypernova",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "Hypernova",
            path: "Sources/Hypernova"
        ),
    ],
    swiftLanguageModes: [.v5]
)
