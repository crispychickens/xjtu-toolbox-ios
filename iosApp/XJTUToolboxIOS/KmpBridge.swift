import Foundation

#if canImport(XJTUToolboxShared)
import XJTUToolboxShared

enum KmpBridgeError: LocalizedError {
    case missingResult(String)

    var errorDescription: String? {
        switch self {
        case .missingResult(let operation):
            return "\(operation) 没有返回结果"
        }
    }
}

final class KmpAuthManagerAdapter: SharedAuthManaging {
    private let authManager: AuthManager

    init(authManager: AuthManager) {
        self.authManager = authManager
    }

    var state: SharedAuthState {
        get async {
            mapState(authManager.authState.value)
        }
    }

    var accessMode: SharedAccessMode {
        get async {
            guard let mode = authManager.currentAccessMode.value as? AccessMode else {
                return .automatic
            }
            return mapAccessMode(mode)
        }
    }

    func setAccessMode(_ mode: SharedAccessMode) async {
        _ = try? await awaitKmpUnit { completion in
            authManager.setAccessMode(mode: mode.kmpValue, completionHandler: completion)
        }
    }

    func restoreSavedCredentials() async -> SharedAuthState {
        do {
            let restored = try await awaitKmp("restoreSavedCredentials") { completion in
                authManager.restoreSavedCredentials(completionHandler: completion)
            }
            return mapState(restored)
        } catch {
            return await state
        }
    }

    func login(username: String, password: String) async -> SharedLoginResult {
        await outcome {
            authManager.login(username: username, password: password, completionHandler: $0)
        }
    }

    func beginBrowserAuth(site: String?) async -> SharedLoginResult {
        await outcome {
            authManager.beginBrowserAuth(site: site.kmpSiteKey, completionHandler: $0)
        }
    }

    func beginSiteVerification(site: String) async -> SharedLoginResult {
        guard let siteKey = site.kmpSiteKey else {
            return .failure(message: "未知站点 \(site)")
        }
        return await outcome {
            authManager.beginSiteVerification(site: siteKey, completionHandler: $0)
        }
    }

    func submitCaptcha(code: String) async -> SharedLoginResult {
        await outcome {
            authManager.submitCaptcha(code: code, completionHandler: $0)
        }
    }

    func submitMfa(code: String) async -> SharedLoginResult {
        await outcome {
            authManager.submitMfa(code: code, completionHandler: $0)
        }
    }

    func submitAccountChoice(choiceId: String) async -> SharedLoginResult {
        await outcome {
            authManager.submitAccountChoice(choiceId: choiceId, completionHandler: $0)
        }
    }

    func resumeBrowserAuth(callbackURL: URL) async -> SharedLoginResult {
        await outcome {
            authManager.resumeBrowserAuth(callbackUrl: callbackURL.absoluteString, completionHandler: $0)
        }
    }

    func ensureSession(site: String) async throws {
        guard let siteKey = site.kmpSiteKey else {
            throw KmpBridgeError.missingResult("未知站点 \(site)")
        }
        _ = try await awaitKmp("ensureSession") { completion in
            authManager.ensureSession(site: siteKey, completionHandler: completion)
        }
    }

    func logout() async {
        _ = try? await awaitKmpUnit { completion in
            authManager.logout(completionHandler: completion)
        }
    }

    private func outcome(
        _ operation: (@escaping @Sendable (LoginOutcome?, Error?) -> Void) -> Void
    ) async -> SharedLoginResult {
        do {
            let result = try await awaitKmp("login outcome", operation)
            return mapOutcome(result)
        } catch {
            return .failure(message: error.localizedDescription)
        }
    }

    private func mapState(_ state: Any?) -> SharedAuthState {
        switch state {
        case is AuthStateAnonymous:
            return .anonymous
        case let state as AuthStateAuthenticating:
            return .authenticating(username: state.username)
        case let state as AuthStateAwaitingCaptcha:
            return .awaitingCaptcha(mapCaptchaChallenge(state.challenge))
        case let state as AuthStateAwaitingMfa:
            return .awaitingMfa(maskedPhone: state.challenge.maskedPhone)
        case let state as AuthStateAwaitingAccountChoice:
            return .awaitingAccountChoice(mapAccountChoiceChallenge(state.challenge))
        case let state as AuthStateAwaitingBrowserAuth:
            return .awaitingBrowserAuth(mapBrowserAuthChallenge(state.challenge))
        case let state as AuthStateAuthenticated:
            return .authenticated(username: state.username)
        case let state as AuthStateSiteVerificationRequired:
            return .siteVerificationRequired(
                username: state.username,
                siteName: state.site.stableName,
                message: state.message
            )
        case let state as AuthStatePasswordInvalidated:
            return .passwordInvalidated(siteName: state.siteName)
        default:
            return .anonymous
        }
    }

    private func mapOutcome(_ outcome: LoginOutcome) -> SharedLoginResult {
        switch outcome {
        case let outcome as LoginOutcomeSuccess:
            return .success(username: outcome.username)
        case let outcome as LoginOutcomeNeedCaptcha:
            return .needCaptcha(mapCaptchaChallenge(outcome.challenge))
        case let outcome as LoginOutcomeNeedMfa:
            return .needMfa(maskedPhone: outcome.challenge.maskedPhone)
        case let outcome as LoginOutcomeNeedAccountChoice:
            return .needAccountChoice(mapAccountChoiceChallenge(outcome.challenge))
        case let outcome as LoginOutcomeNeedBrowserAuth:
            return .needBrowserAuth(mapBrowserAuthChallenge(outcome.challenge))
        case let outcome as LoginOutcomeFailure:
            return .failure(message: mapFailure(outcome.reason))
        default:
            return .failure(message: "未知登录结果")
        }
    }

    private func mapFailure(_ failure: AuthFailure) -> String {
        switch failure {
        case let failure as AuthFailureInvalidPassword:
            return "密码验证失败：\(failure.siteName)"
        case let failure as AuthFailureRateLimited:
            let seconds = max(1, Int(ceil(Double(failure.retryAfterMillis) / 1_000.0)))
            return "请求过于频繁，请 \(seconds) 秒后重试"
        case let failure as AuthFailureBrowserAuthRejected:
            return failure.message
        case let failure as AuthFailureVerificationRejected:
            return failure.message
        case let failure as AuthFailureNetwork:
            return failure.message
        case let failure as AuthFailureServiceChanged:
            return failure.message
        case let failure as AuthFailureUnknown:
            return failure.message
        default:
            return "登录失败"
        }
    }

    private func mapBrowserAuthChallenge(_ challenge: BrowserAuthChallenge) -> SharedBrowserAuthRequest {
        SharedBrowserAuthRequest(
            loginURL: challenge.loginUrl,
            callbackScheme: challenge.callbackScheme,
            state: challenge.state,
            site: challenge.site?.stableName
        )
    }

    private func mapCaptchaChallenge(_ challenge: CaptchaChallenge) -> SharedCaptchaChallenge {
        SharedCaptchaChallenge(
            imageBase64: challenge.imageBase64,
            site: challenge.site?.stableName
        )
    }

    private func mapAccountChoiceChallenge(_ challenge: AccountChoiceChallenge) -> SharedAccountChoiceChallenge {
        SharedAccountChoiceChallenge(
            choices: challenge.choices.map { choice in
                SharedAccountChoice(
                    id: choice.id,
                    displayName: choice.displayName,
                    accountType: mapAccountType(choice.accountType)
                )
            },
            site: challenge.site?.stableName
        )
    }

    private func mapAccountType(_ accountType: AccountType) -> SharedAccountType {
        switch accountType {
        case AccountType.undergraduate:
            return .undergraduate
        case AccountType.postgraduate:
            return .postgraduate
        default:
            return .unknown
        }
    }
}

final class KmpFeatureProviderAdapter: SharedFeatureProviding, FeatureCacheClearing {
    private let service: CoreFeatureService
    private let clearCacheAction: () -> Void

    init(service: CoreFeatureService, clearCache: @escaping () -> Void = {}) {
        self.service = service
        self.clearCacheAction = clearCache
    }

    func clearCachedFeatures() {
        clearCacheAction()
    }

    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        let snapshot = try await awaitKmp("dashboard") { completion in
            service.dashboard(
                dayOfWeek: Int32(dayOfWeek),
                noticePageSize: 3,
                completionHandler: completion
            )
        }
        return SharedDashboardSnapshot(
            todayCourses: snapshot.todayCourses.map(mapCourse),
            campusCard: snapshot.campusCard.map(mapCampusCardInfo),
            notices: snapshot.notices.map(mapNotice)
        )
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        let snapshot = try await awaitKmp("schedule") { completion in
            service.schedule(termCode: nil, completionHandler: completion)
        }
        return SharedScheduleSnapshot(
            courses: snapshot.courses.map(mapCourse),
            exams: snapshot.exams.map(mapExam),
            textbooks: snapshot.textbooks.map(mapTextbook)
        )
    }

    func grades() async throws -> SharedGradeSnapshot {
        let snapshot = try await awaitKmp("grades") { completion in
            service.grades(termCode: nil, completionHandler: completion)
        }
        return SharedGradeSnapshot(
            grades: snapshot.grades.map(mapGrade),
            weightedGpa: snapshot.weightedGpa?.doubleValue,
            totalCredits: snapshot.totalCredits
        )
    }

    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail {
        let detail = try await awaitKmp("grade detail") { completion in
            service.gradeDetail(gradeId: gradeId, completionHandler: completion)
        }
        return SharedGradeDetail(
            courseName: detail.courseName,
            score: detail.score,
            credit: detail.credit,
            gradePoint: detail.gradePoint,
            examType: detail.examType,
            courseProperty: detail.courseProperty,
            examProperty: detail.examProperty,
            isReplacement: detail.isReplacement,
            isPassed: detail.isPassed,
            specificReason: detail.specificReason,
            items: detail.items.map {
                SharedGradeDetailItem(name: $0.name, percent: $0.percent, score: $0.score)
            }
        )
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        let snapshot = try await awaitKmp("campus card") { completion in
            service.campusCard(
                page: Int32(page),
                pageSize: Int32(pageSize),
                completionHandler: completion
            )
        }
        return SharedCampusCardSnapshot(
            info: mapCampusCardInfo(snapshot.info),
            transactions: snapshot.transactions.map(mapTransaction),
            totalTransactions: Int(snapshot.totalTransactions)
        )
    }

    func noticePage(page: Int) async throws -> SharedNoticePage {
        let page = try await awaitKmp("notice page") { completion in
            service.noticePage(page: Int32(page), completionHandler: completion)
        }
        return SharedNoticePage(
            total: Int(page.total),
            records: page.records.map(mapNotice)
        )
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        let kmpSections = KotlinIntRange(
            start: Int32(sections.lowerBound),
            endInclusive: Int32(sections.upperBound)
        )
        let rooms = try await awaitKmp("empty rooms") { completion in
            service.emptyRooms(
                campus: campus,
                date: date,
                sections: kmpSections,
                completionHandler: completion
            )
        }
        return rooms.map(mapEmptyRoom)
    }

    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot {
        let snapshot = try await awaitKmp("library seats") { completion in
            service.librarySeats(areaCode: areaCode, completionHandler: completion)
        }
        return mapLibrarySeatSnapshot(snapshot)
    }

    func bookLibrarySeat(seatId: String, areaCode: String, allowSwap: Bool) async throws -> SharedLibrarySeatBookingResult {
        let result = try await awaitKmp("book library seat") { completion in
            service.bookLibrarySeat(
                seatId: seatId,
                areaCode: areaCode,
                allowSwap: allowSwap,
                completionHandler: completion
            )
        }
        return SharedLibrarySeatBookingResult(
            success: result.success,
            message: result.message,
            finalURL: result.finalUrl
        )
    }

    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage {
        let couponPage = try await awaitKmp("coupons") { completion in
            service.coupons(
                filter: filter.kmpValue,
                page: Int32(page),
                pageSize: Int32(pageSize),
                completionHandler: completion
            )
        }
        return SharedCouponPage(
            filter: mapCouponFilter(couponPage.filter),
            total: Int(couponPage.total),
            records: couponPage.records.map(mapCoupon)
        )
    }

    func schoolCourses(
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int
    ) async throws -> SharedSchoolCoursePage {
        let result = try await awaitKmp("school courses") { completion in
            service.schoolCourses(
                termCode: termCode,
                courseName: courseName,
                teacher: teacher,
                campusCode: campusCode,
                weekday: Int32(weekday),
                page: Int32(page),
                pageSize: Int32(pageSize),
                completionHandler: completion
            )
        }
        return SharedSchoolCoursePage(
            termCode: result.termCode,
            total: Int(result.total),
            page: Int(result.page),
            pageSize: Int(result.pageSize),
            records: result.records.map(mapSchoolCourse)
        )
    }

    private func mapCourse(_ item: CourseItem) -> SharedCourseItem {
        SharedCourseItem(
            id: "\(item.name)|\(item.dayOfWeek)|\(item.startSection)|\(item.location)",
            name: item.name,
            teacher: item.teacher,
            location: item.location,
            dayOfWeek: Int(item.dayOfWeek),
            startSection: Int(item.startSection),
            endSection: Int(item.endSection),
            weeks: item.weeks.map { Int($0.int32Value) }
        )
    }

    private func mapExam(_ item: ExamItem) -> SharedExamItem {
        SharedExamItem(
            id: "\(item.courseName)|\(item.time)|\(item.location)",
            courseName: item.courseName,
            time: item.time,
            location: item.location,
            courseCode: item.courseCode,
            examDate: item.examDate,
            examTime: item.examTime,
            seatNumber: item.seatNumber
        )
    }

    private func mapTextbook(_ item: TextbookItem) -> SharedTextbookItem {
        SharedTextbookItem(
            id: "\(item.courseName)|\(item.textbookName)|\(item.isbn)",
            courseName: item.courseName,
            textbookName: item.textbookName,
            author: item.author,
            publisher: item.publisher,
            isbn: item.isbn,
            edition: item.edition,
            price: item.price,
            hasSubstantiveTextbook: item.hasSubstantiveTextbook
        )
    }

    private func mapGrade(_ item: GradeItem) -> SharedGradeItem {
        let fallbackID = "\(item.termCode)|\(item.courseName)|\(item.score)|\(item.credit)"
        return SharedGradeItem(
            id: item.id.isEmpty ? fallbackID : item.id,
            courseName: item.courseName,
            score: item.score,
            credit: item.credit,
            gradePoint: item.gradePoint,
            hasDetail: !item.id.isEmpty,
            termCode: item.termCode
        )
    }

    private func mapCampusCardInfo(_ item: CampusCardInfo) -> SharedCampusCardInfo {
        SharedCampusCardInfo(
            balanceYuan: item.balanceYuan,
            holderName: item.holderName
        )
    }

    private func mapTransaction(_ item: CampusCardTransaction) -> SharedCampusCardTransaction {
        SharedCampusCardTransaction(
            id: "\(item.time)|\(item.merchant)|\(item.amountYuan)",
            time: item.time,
            merchant: item.merchant,
            amountYuan: item.amountYuan,
            isIncome: item.kind.name == "INCOME",
            balanceAfterYuan: item.balanceAfterYuan?.doubleValue
        )
    }

    private func mapNotice(_ item: NoticeItem) -> SharedNoticeItem {
        SharedNoticeItem(
            id: "\(item.source)|\(item.title)|\(item.link)",
            title: item.title,
            link: item.link,
            source: item.source,
            date: item.date
        )
    }

    private func mapEmptyRoom(_ item: EmptyRoom) -> SharedEmptyRoom {
        SharedEmptyRoom(
            id: "\(item.campus)|\(item.building)|\(item.name)",
            name: item.name,
            campus: item.campus,
            building: item.building,
            availableSections: item.availableSections.map { Int($0.int32Value) },
            capacity: Int(item.capacity)
        )
    }

    private func mapLibrarySeatSnapshot(_ snapshot: LibrarySeatSnapshot) -> SharedLibrarySeatSnapshot {
        SharedLibrarySeatSnapshot(
            selectedAreaCode: snapshot.selectedAreaCode,
            areas: snapshot.areas.map(mapLibraryArea),
            seats: snapshot.seats.map(mapLibrarySeat),
            recommendedAreas: snapshot.recommendedAreas.map(mapLibraryArea),
            myBooking: snapshot.myBooking.map(mapLibraryBooking)
        )
    }

    private func mapLibraryArea(_ item: LibraryAreaStats) -> SharedLibraryAreaStats {
        SharedLibraryAreaStats(
            code: item.code,
            name: item.name,
            floor: item.floor,
            available: Int(item.available),
            total: Int(item.total)
        )
    }

    private func mapLibrarySeat(_ item: LibrarySeatItem) -> SharedLibrarySeatItem {
        SharedLibrarySeatItem(
            seatId: item.seatId,
            available: item.available
        )
    }

    private func mapLibraryBooking(_ item: LibraryBookingInfo) -> SharedLibraryBookingInfo {
        SharedLibraryBookingInfo(
            seatId: item.seatId,
            areaName: item.areaName,
            statusText: item.statusText
        )
    }

    private func mapCoupon(_ item: CouponRecord) -> SharedCouponRecord {
        let fallbackID = "\(item.showCardId)|\(item.sendId)|\(item.voucherName)"
        return SharedCouponRecord(
            id: item.showCardId.isEmpty ? fallbackID : item.showCardId,
            sendId: item.sendId,
            showCardId: item.showCardId,
            voucherName: item.voucherName,
            typeName: item.typeName,
            amountYuan: item.amountYuan,
            leftAmountYuan: item.leftAmountYuan,
            leftCount: Int(item.leftCount),
            startDate: item.startDate,
            endDate: item.endDate,
            imageURL: item.imageUrl
        )
    }

    private func mapCouponFilter(_ filter: CouponFilter) -> SharedCouponFilter {
        switch filter.name {
        case "AVAILABLE":
            return .available
        case "USED_UP":
            return .usedUp
        case "EXPIRED":
            return .expired
        default:
            return .usable
        }
    }

    private func mapSchoolCourse(_ item: SchoolCourseItem) -> SharedSchoolCourse {
        let fallbackID = "\(item.termCode)|\(item.courseCode)|\(item.sectionNumber)|\(item.teacher)"
        return SharedSchoolCourse(
            id: item.teachingClassId.isEmpty ? fallbackID : item.teachingClassId,
            courseCode: item.courseCode,
            courseName: item.courseName,
            sectionNumber: item.sectionNumber,
            teacher: item.teacher,
            department: item.department,
            credit: item.credit,
            enrollCount: Int(item.enrollCount),
            capacity: Int(item.capacity),
            scheduleLocation: item.scheduleLocation,
            campus: item.campus,
            termCode: item.termCode
        )
    }
}

private func awaitKmp<T>(
    _ operationName: String,
    _ operation: (@escaping @Sendable (T?, Error?) -> Void) -> Void
) async throws -> T {
    try await withCheckedThrowingContinuation { continuation in
        operation { value, error in
            if let error {
                continuation.resume(throwing: error)
            } else if let value {
                continuation.resume(returning: value)
            } else {
                continuation.resume(throwing: KmpBridgeError.missingResult(operationName))
            }
        }
    }
}

private func awaitKmpUnit(
    _ operation: (@escaping @Sendable (Error?) -> Void) -> Void
) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        operation { error in
            if let error {
                continuation.resume(throwing: error)
            } else {
                continuation.resume()
            }
        }
    }
}

func mapAccessMode(_ mode: AccessMode) -> SharedAccessMode {
    switch mode.name {
    case "NORMAL":
        return .normal
    case "WEBVPN":
        return .webvpn
    default:
        return .automatic
    }
}

extension SharedAccessMode {
    var kmpValue: AccessMode {
        switch self {
        case .automatic:
            return AccessMode.auto_
        case .normal:
            return AccessMode.normal
        case .webvpn:
            return AccessMode.webvpn
        }
    }
}

extension SharedCouponFilter {
    var kmpValue: CouponFilter {
        switch self {
        case .available:
            return CouponFilter.available
        case .usable:
            return CouponFilter.usable
        case .usedUp:
            return CouponFilter.usedUp
        case .expired:
            return CouponFilter.expired
        }
    }
}

private extension Optional where Wrapped == String {
    var kmpSiteKey: SiteKey? {
        flatMap { $0.kmpSiteKey }
    }
}

private extension String {
    var kmpSiteKey: SiteKey? {
        switch lowercased() {
        case "jwxt":
            return SiteKey.jwxt
        case "jwapp":
            return SiteKey.jwapp
        case "ywtb":
            return SiteKey.ywtb
        case "campus_card", "campuscard":
            return SiteKey.campusCard
        case "notice":
            return SiteKey.notice
        case "empty_room", "emptyroom":
            return SiteKey.emptyRoom
        case "schedule":
            return SiteKey.schedule
        case "grade":
            return SiteKey.grade
        case "library":
            return SiteKey.library
        case "coupon":
            return SiteKey.coupon
        default:
            return nil
        }
    }
}
#endif
