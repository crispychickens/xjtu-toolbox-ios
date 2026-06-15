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

    private func launchPreview(
        tab: String,
        tool: String? = nil,
        scheduleView: String? = nil
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

        app.launch()
        return app
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
