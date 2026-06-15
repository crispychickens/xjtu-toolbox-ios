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

    func testPreviewHomeLoadsDashboard() {
        let app = launchPreview(tab: "home")

        assertExists(app.navigationBars["首页"])
        assertExists(app.staticTexts["今日无课"])
        assertExists(app.staticTexts["关于考试安排的通知"])
    }

    func testPreviewScheduleViewsLoad() {
        var app = launchPreview(tab: "schedule", scheduleView: "courses")

        assertExists(app.navigationBars["日程"])
        assertExists(app.staticTexts["高等数学"])
        assertExists(app.staticTexts["大学物理"])
        assertExists(app.staticTexts["线性代数"])

        app = launchPreview(tab: "schedule", scheduleView: "exams")
        assertExists(app.staticTexts["当前学期考试安排"])
        assertExists(app.staticTexts["12 号"])

        app = launchPreview(tab: "schedule", scheduleView: "textbooks")
        assertExists(app.staticTexts["1 门课程有教材"])
        assertExists(app.staticTexts["高等数学 第八版"])
    }

    func testPreviewAcademicToolsLoad() {
        var app = launchPreview(tab: "tools", tool: "grades")

        assertExists(app.navigationBars["学辅"])
        assertExists(app.staticTexts["GPA 3.90 · 3.0 学分 · 1 门"])
        assertExists(app.staticTexts["92"])

        app = launchPreview(tab: "tools", tool: "emptyRooms")
        assertExists(app.staticTexts["2 间符合条件"])
        assertExists(app.staticTexts["中二-3201"])

        app = launchPreview(tab: "tools", tool: "schoolCourses")
        assertExists(app.staticTexts["全校课程查询"])
        assertExists(app.buttons["查询课程"])
    }

    func testPreviewCampusServicesLoad() {
        var app = launchPreview(tab: "tools", tool: "campusCard")

        assertExists(app.staticTexts["已加载流水"])
        assertExists(app.staticTexts["康桥苑"])

        app = launchPreview(tab: "tools", tool: "librarySeats")
        assertExists(app.staticTexts["当前预约"])
        assertExists(app.staticTexts["D021"])

        app = launchPreview(tab: "tools", tool: "coupons")
        assertExists(app.staticTexts["康桥苑加餐券"])
        assertExists(app.staticTexts["可使用"])
    }

    func testPreviewAuthenticationChallengesComplete() {
        var app = launchFreshLogin()
        submitLogin(app, password: "captcha")
        assertExists(app.navigationBars["图形验证码"])
        app.textFields["图形验证码"].tap()
        app.textFields["图形验证码"].typeText("1234")
        app.buttons["继续登录"].tap()
        assertExists(app.navigationBars["日程"])

        app = launchFreshLogin()
        submitLogin(app, password: "mfa")
        assertExists(app.navigationBars["手机验证"])
        app.textFields["6 位验证码"].tap()
        app.textFields["6 位验证码"].typeText("123456")
        app.buttons["验证"].tap()
        assertExists(app.navigationBars["日程"])

        app = launchPreviewAccountChoice()
        assertExists(app.navigationBars["账号类型"])
        assertExists(app.staticTexts["请选择账号类型"])
        app.buttons["继续登录"].tap()
        assertExists(app.navigationBars["日程"])
    }

    func testPreviewEmptyAndRetryRecoveryStates() {
        var app = launchPreview(
            tab: "tools",
            tool: "emptyRooms",
            extraArguments: [ "-XJTUPreviewEmptyFeature", "emptyRooms" ]
        )
        assertExists(app.staticTexts["当前筛选暂无空闲教室"])

        app = launchPreview(
            tab: "tools",
            tool: "librarySeats",
            extraArguments: [ "-XJTUPreviewFailOnceFeature", "librarySeats" ]
        )
        assertExists(app.staticTexts["数据加载失败：预览恢复场景：首次加载失败"])
        assertExists(app.buttons["重试图书馆座位"])
        app.buttons["重试图书馆座位"].tap()
        assertExists(app.staticTexts["D021"])
    }

    private func launchPreview(
        tab: String,
        tool: String? = nil,
        scheduleView: String? = nil,
        extraArguments: [String] = []
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-XJTURequireFreshLogin",
            "-XJTUPreviewAutoLogin",
            "-XJTUStartTab",
            tab,
        ]
        if let tool {
            app.launchArguments += [ "-XJTUStartTool", tool ]
        }
        if let scheduleView {
            app.launchArguments += [ "-XJTUStartScheduleView", scheduleView ]
        }
        app.launchArguments += extraArguments

        app.launch()
        return app
    }

    private func launchFreshLogin(extraArguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [ "-XJTURequireFreshLogin" ] + extraArguments
        app.launch()
        assertExists(app.staticTexts["统一身份认证"])
        return app
    }

    private func launchPreviewAccountChoice() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "-XJTURequireFreshLogin",
            "-XJTUPreviewAccountChoice",
        ]
        app.launch()
        return app
    }

    private func submitLogin(_ app: XCUIApplication, password: String) {
        let usernameField = app.textFields["学号 / 手机号"]
        usernameField.tap()
        usernameField.typeText("3124000000")
        let passwordField = app.secureTextFields["密码"]
        passwordField.tap()
        passwordField.typeText(password)
        app.buttons["登录"].tap()
    }

    private func assertExists(
        _ element: XCUIElement,
        timeout: TimeInterval = 10,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), file: file, line: line)
    }
}
