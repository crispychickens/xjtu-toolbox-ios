import XCTest

final class PreviewSmokeUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    func testDefaultDebugLaunchShowsLoginScreen() {
        let app = XCUIApplication()
        app.launchArguments = [ "-XJTURequireFreshLogin" ]

        app.launch()

        XCTAssertTrue(app.staticTexts["统一身份认证"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["登录"].exists)
        XCTAssertFalse(app.tabBars.buttons["日程"].exists)
    }

    func testPreviewAutoLoginLoadsScheduleTab() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-XJTURequireFreshLogin",
            "-XJTUPreviewAutoLogin",
            "-XJTUStartTab",
            "schedule",
        ]

        app.launch()

        XCTAssertTrue(app.navigationBars["日程"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["日程"].exists)
        XCTAssertTrue(app.staticTexts["高等数学"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["大学物理"].exists)
        XCTAssertTrue(app.staticTexts["线性代数"].exists)
    }
}
