import XCTest
@testable import XJTUToolboxIOS

final class LaunchArgumentsTests: XCTestCase {
    func testDebugBuildDefaultsToPreviewDependencies() {
        XCTAssertEqual(
            XjtuLaunchArguments.dependencyMode(arguments: [], buildConfiguration: .debug),
            .preview
        )
    }

    func testDebugBuildAllowsExplicitValidationDependencies() {
        XCTAssertEqual(
            XjtuLaunchArguments.dependencyMode(
                arguments: [XjtuLaunchArguments.realLoginValidation],
                buildConfiguration: .debug
            ),
            .realLoginValidation
        )
        XCTAssertEqual(
            XjtuLaunchArguments.dependencyMode(
                arguments: [XjtuLaunchArguments.realFirstReleaseCore],
                buildConfiguration: .debug
            ),
            .realFirstReleaseCore
        )
    }

    func testReleaseBuildDefaultsToRealFirstReleaseDependencies() {
        XCTAssertEqual(
            XjtuLaunchArguments.dependencyMode(arguments: [], buildConfiguration: .release),
            .realFirstReleaseCore
        )
    }

    func testReleaseBuildIgnoresPreviewBackedValidationModes() {
        let previewBackedArguments: Set<String> = [
            XjtuLaunchArguments.realLoginValidation,
            XjtuLaunchArguments.realEmptyRooms,
            XjtuLaunchArguments.realPublicData,
            XjtuLaunchArguments.realCampusCardAndPublicData,
            XjtuLaunchArguments.previewAutoLogin,
            XjtuLaunchArguments.previewAccountChoice,
            XjtuLaunchArguments.previewEmptyFeature,
            XjtuLaunchArguments.previewFailOnceFeature,
        ]

        XCTAssertEqual(
            XjtuLaunchArguments.dependencyMode(
                arguments: previewBackedArguments,
                buildConfiguration: .release
            ),
            .realFirstReleaseCore
        )
    }

    func testPreviewRecoveryScenariosAreDebugPreviewOnly() {
        let rawArguments = [
            XjtuLaunchArguments.previewFailOnceFeature,
            PreviewRecoveryFeature.librarySeats.rawValue,
        ]

        XCTAssertEqual(
            XjtuLaunchArguments.previewRecoveryScenario(
                rawArguments: rawArguments,
                dependencyMode: .preview,
                buildConfiguration: .debug
            ),
            .failOnce(.librarySeats)
        )
        XCTAssertNil(
            XjtuLaunchArguments.previewRecoveryScenario(
                rawArguments: rawArguments,
                dependencyMode: .realFirstReleaseCore,
                buildConfiguration: .debug
            )
        )
        XCTAssertNil(
            XjtuLaunchArguments.previewRecoveryScenario(
                rawArguments: rawArguments,
                dependencyMode: .preview,
                buildConfiguration: .release
            )
        )
    }

    func testAuthNetworkDebugLoggingIsDebugOnly() {
        let arguments: Set<String> = [XjtuLaunchArguments.authNetworkDebug]

        XCTAssertTrue(
            XjtuLaunchArguments.shouldLogAuthNetworkDebug(
                arguments: arguments,
                buildConfiguration: .debug
            )
        )
        XCTAssertFalse(
            XjtuLaunchArguments.shouldLogAuthNetworkDebug(
                arguments: arguments,
                buildConfiguration: .release
            )
        )
    }

    func testAutoSiteVerificationIsDebugOnly() {
        let arguments: Set<String> = [XjtuLaunchArguments.autoSiteVerification]

        XCTAssertTrue(
            XjtuLaunchArguments.shouldAutoBeginSiteVerification(
                arguments: arguments,
                buildConfiguration: .debug
            )
        )
        XCTAssertFalse(
            XjtuLaunchArguments.shouldAutoBeginSiteVerification(
                arguments: arguments,
                buildConfiguration: .release
            )
        )
    }

    func testRealFeatureValidationIsDebugOnly() {
        let arguments: Set<String> = [XjtuLaunchArguments.realFeatureValidation]

        XCTAssertTrue(
            XjtuLaunchArguments.shouldRunRealFeatureValidation(
                arguments: arguments,
                buildConfiguration: .debug
            )
        )
        XCTAssertFalse(
            XjtuLaunchArguments.shouldRunRealFeatureValidation(
                arguments: arguments,
                buildConfiguration: .release
            )
        )
    }

    func testPersistentFeatureCachePrefixSeparatesDebugAndRelease() {
        let debugPreview = XjtuLaunchArguments.persistentFeatureCacheKeyPrefix(
            dependencyMode: .preview,
            buildConfiguration: .debug
        )
        let debugReal = XjtuLaunchArguments.persistentFeatureCacheKeyPrefix(
            dependencyMode: .realFirstReleaseCore,
            buildConfiguration: .debug
        )
        let releaseReal = XjtuLaunchArguments.persistentFeatureCacheKeyPrefix(
            dependencyMode: .realFirstReleaseCore,
            buildConfiguration: .release
        )

        XCTAssertNotEqual(debugPreview, debugReal)
        XCTAssertNotEqual(debugReal, releaseReal)
        XCTAssertFalse(releaseReal.contains("preview"))
        XCTAssertTrue(releaseReal.hasPrefix("com.xjtu.toolbox.ios.featureCache.release.realFirstReleaseCore."))
    }
}
