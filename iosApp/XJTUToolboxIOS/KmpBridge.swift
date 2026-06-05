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
            transactions: snapshot.transactions.map(mapTransaction)
        )
    }

    func notices(page: Int) async throws -> [SharedNoticeItem] {
        let notices = try await awaitKmp("notices") { completion in
            service.notices(page: Int32(page), completionHandler: completion)
        }
        return notices.map(mapNotice)
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
            location: item.location
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
            hasSubstantiveTextbook: item.hasSubstantiveTextbook
        )
    }

    private func mapGrade(_ item: GradeItem) -> SharedGradeItem {
        SharedGradeItem(
            id: "\(item.courseName)|\(item.score)|\(item.credit)",
            courseName: item.courseName,
            score: item.score,
            credit: item.credit,
            gradePoint: item.gradePoint
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
            isIncome: item.kind.name == "INCOME"
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
            availableSections: item.availableSections.map { Int($0.int32Value) }
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
        default:
            return nil
        }
    }
}
#endif
