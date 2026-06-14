import XCTest
@testable import XJTUToolboxIOS

#if canImport(XJTUToolboxShared)
import XJTUToolboxShared

final class KmpBridgeContractTests: XCTestCase {
    func testAuthBridgeMapsChallengesFailuresAndAccessModes() async throws {
        let services = XjtuToolboxPreviewFactory.shared.appServices(initialAccessMode: AccessMode.auto_)
        let subject = KmpAuthManagerAdapter(authManager: services.authManager)

        let initialState = await subject.state
        XCTAssertEqual(initialState, .anonymous)

        let accountChoice = await subject.login(username: "3124000000", password: "account")
        XCTAssertEqual(
            accountChoice,
            .needAccountChoice(
                SharedAccountChoiceChallenge(
                    choices: [
                        SharedAccountChoice(
                            id: "undergraduate-preview",
                            displayName: "本科生账号",
                            accountType: .undergraduate
                        ),
                        SharedAccountChoice(
                            id: "postgraduate-preview",
                            displayName: "研究生账号",
                            accountType: .postgraduate
                        ),
                    ],
                    site: nil
                )
            )
        )

        let awaitingChoice = await subject.state
        guard case .awaitingAccountChoice(let challenge) = awaitingChoice else {
            return XCTFail("Expected the KMP auth state to map to awaitingAccountChoice")
        }
        XCTAssertEqual(challenge.choices.map(\.accountType), [.undergraduate, .postgraduate])

        let success = await subject.submitAccountChoice(choiceId: "undergraduate-preview")
        XCTAssertEqual(success, .success(username: "3124000000"))

        for mode in SharedAccessMode.allCases {
            await subject.setAccessMode(mode)
            let mappedMode = await subject.accessMode
            XCTAssertEqual(mappedMode, mode)
        }

        let unknownSite = await subject.beginSiteVerification(site: "unknown")
        XCTAssertEqual(unknownSite, .failure(message: "未知站点 unknown"))

        do {
            try await subject.ensureSession(site: "unknown")
            XCTFail("Unknown sites must fail before reaching the KMP AuthManager")
        } catch {
            XCTAssertEqual(error.localizedDescription, "未知站点 unknown 没有返回结果")
        }

        let blankServices = XjtuToolboxPreviewFactory.shared.appServices(initialAccessMode: AccessMode.auto_)
        let blankSubject = KmpAuthManagerAdapter(authManager: blankServices.authManager)
        let blankLogin = await blankSubject.login(username: "", password: "")
        XCTAssertEqual(blankLogin, .failure(message: "用户名和密码不能为空"))
    }

    func testFeatureBridgeMapsFirstReleasePreviewSurface() async throws {
        let services = XjtuToolboxPreviewFactory.shared.appServices(initialAccessMode: AccessMode.auto_)
        let auth = KmpAuthManagerAdapter(authManager: services.authManager)
        let subject = KmpFeatureProviderAdapter(service: services.coreFeatureService)

        let login = await auth.login(username: "3124000000", password: "secret")
        XCTAssertEqual(login, .success(username: "3124000000"))

        let dashboard = try await subject.dashboard(dayOfWeek: 3)
        XCTAssertEqual(dashboard.todayCourses.map(\.name), ["高等数学", "大学物理"])
        XCTAssertEqual(dashboard.campusCard, SharedCampusCardInfo(balanceYuan: 42.5, holderName: "学生"))
        XCTAssertEqual(dashboard.notices.count, 3)

        let schedule = try await subject.schedule()
        XCTAssertEqual(schedule.courses.count, 3)
        XCTAssertEqual(schedule.courses.first?.id, "高等数学|3|1|主楼-101")
        XCTAssertEqual(schedule.courses.first?.weeks, Array(1...16))
        XCTAssertEqual(schedule.exams.first?.seatNumber, "12")
        XCTAssertEqual(schedule.textbooks.map(\.hasSubstantiveTextbook), [true, false])

        let grades = try await subject.grades()
        XCTAssertEqual(grades.grades.map(\.id), ["preview-grade-1", "preview-grade-2"])
        XCTAssertEqual(grades.weightedGpa ?? 0, 3.64, accuracy: 0.0001)
        XCTAssertEqual(grades.totalCredits, 5)

        let detail = try await subject.gradeDetail(gradeId: "preview-grade-1")
        XCTAssertEqual(detail.courseName, "高等数学")
        XCTAssertEqual(detail.items.map(\.name), ["平时成绩", "期末考试"])
        XCTAssertTrue(detail.isPassed)

        let card = try await subject.campusCard(page: 1, pageSize: 10)
        XCTAssertEqual(card.totalTransactions, 2)
        XCTAssertEqual(card.transactions.map(\.isIncome), [false, true])
        XCTAssertEqual(card.transactions.last?.balanceAfterYuan, 130.5)

        let notices = try await subject.noticePage(page: 1)
        XCTAssertEqual(notices.total, 3)
        XCTAssertEqual(notices.records.first?.source, "教务处")

        let rooms = try await subject.emptyRooms(
            campus: "兴庆校区",
            date: "2026-06-14",
            sections: 2...3
        )
        XCTAssertEqual(rooms.first?.availableSections, [2, 3])
        XCTAssertEqual(rooms.first?.capacity, 120)

        let library = try await subject.librarySeats(areaCode: "north4middle")
        XCTAssertEqual(library.selectedAreaCode, "north4middle")
        XCTAssertEqual(library.areas.count, 4)
        XCTAssertEqual(library.seats.filter(\.available).count, 3)
        XCTAssertEqual(library.recommendedAreas.first?.code, "north4middle")
        XCTAssertEqual(library.myBooking?.seatId, "D021")

        let booking = try await subject.bookLibrarySeat(
            seatId: "d024",
            areaCode: "north4middle",
            allowSwap: false
        )
        XCTAssertEqual(
            booking,
            SharedLibrarySeatBookingResult(
                success: true,
                message: "座位 D024 预约成功",
                finalURL: "http://rg.lib.xjtu.edu.cn:8086/my/"
            )
        )

        for filter in SharedCouponFilter.allCases {
            let couponPage = try await subject.coupons(filter: filter, page: 1, pageSize: 10)
            XCTAssertEqual(couponPage.filter, filter)
        }
        let usedCoupons = try await subject.coupons(filter: .usedUp, page: 1, pageSize: 10)
        XCTAssertEqual(usedCoupons.records.first?.id, "meal-20260515")
        XCTAssertEqual(usedCoupons.records.first?.leftAmountYuan, 0)

        let courses = try await subject.schoolCourses(
            termCode: "2025-2026-2",
            courseName: "",
            teacher: "陈",
            campusCode: "5",
            weekday: 0,
            page: 1,
            pageSize: 10
        )
        XCTAssertEqual(courses.total, 1)
        XCTAssertEqual(courses.records.first?.id, "preview-school-course-3")
        XCTAssertEqual(courses.records.first?.campus, "创新港校区")
    }
}
#endif
