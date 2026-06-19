import XCTest
@testable import XJTUToolboxIOS

@MainActor
final class AuthStoreTests: XCTestCase {
    private let sessionContextKey = "auth.hasAuthenticatedSessionContext"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: sessionContextKey)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: sessionContextKey)
        super.tearDown()
    }

    func testSuccessfulLoginPersistsAuthenticatedSessionContext() async {
        let authManager = AuthManagerSpy()
        let subject = AuthStore(authManager: authManager)
        subject.username = "3124000000"
        subject.password = "secret"

        await subject.login()

        XCTAssertEqual(subject.state, .authenticated(username: "3124000000"))
        XCTAssertTrue(subject.isAuthenticated)
        XCTAssertTrue(subject.hasAuthenticatedSessionContext)
        XCTAssertTrue(UserDefaults.standard.bool(forKey: sessionContextKey))
        XCTAssertEqual(authManager.loginRequests, [AuthManagerSpy.LoginRequest(username: "3124000000", password: "secret")])
    }

    func testAuthenticatedStateClearsStaleErrorMessage() async {
        let authManager = AuthManagerSpy()
        authManager.currentState = .authenticated(username: "3124000000")
        UserDefaults.standard.set(true, forKey: sessionContextKey)
        let subject = AuthStore(authManager: authManager)
        subject.username = "3124000000"
        subject.password = "secret"
        authManager.siteVerificationResult = .failure(message: "CAS 仍返回登录表单")
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "coupon",
            message: "需要补授权"
        )
        _ = await subject.syncStateFromManager()
        await subject.beginSiteVerification()
        XCTAssertNotNil(subject.errorMessage)

        authManager.currentState = .authenticated(username: "3124000000")
        _ = await subject.syncStateFromManager()

        XCTAssertNil(subject.errorMessage)
        XCTAssertEqual(subject.state, .authenticated(username: "3124000000"))
    }

    func testLogoutClearsAuthenticatedSessionContextAndLocalInputs() async {
        let authManager = AuthManagerSpy()
        let subject = AuthStore(authManager: authManager)
        subject.username = "3124000000"
        subject.password = "secret"
        await subject.login()
        subject.captchaCode = "abcd"
        subject.mfaCode = "123456"
        subject.selectedAccountChoiceId = "undergraduate-preview"

        await subject.logout()

        XCTAssertEqual(subject.state, .anonymous)
        XCTAssertFalse(subject.isAuthenticated)
        XCTAssertFalse(subject.hasAuthenticatedSessionContext)
        XCTAssertFalse(UserDefaults.standard.bool(forKey: sessionContextKey))
        XCTAssertEqual(subject.username, "")
        XCTAssertEqual(subject.password, "")
        XCTAssertEqual(subject.captchaCode, "")
        XCTAssertEqual(subject.mfaCode, "")
        XCTAssertEqual(subject.selectedAccountChoiceId, "")
        XCTAssertEqual(authManager.logoutRequests, 1)
    }

    func testSiteVerificationRequiredAfterAuthenticatedContextKeepsShellAuthenticated() async {
        let authManager = AuthManagerSpy()
        let subject = AuthStore(authManager: authManager)
        subject.username = "3124000000"
        subject.password = "secret"
        await subject.login()
        subject.password = "stale-password"
        subject.captchaCode = "abcd"
        subject.mfaCode = "123456"
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "schedule",
            message: "CAS 仍返回登录表单"
        )

        _ = await subject.syncStateFromManager()

        XCTAssertEqual(
            subject.state,
            .siteVerificationRequired(
                username: "3124000000",
                siteName: "schedule",
                message: "CAS 仍返回登录表单"
            )
        )
        XCTAssertTrue(subject.isAuthenticated)
        XCTAssertTrue(subject.shouldShowInlineSiteVerification)
        XCTAssertTrue(subject.hasPendingSiteVerification)
        XCTAssertNil(subject.errorMessage)
        XCTAssertEqual(subject.password, "")
        XCTAssertEqual(subject.captchaCode, "")
        XCTAssertEqual(subject.mfaCode, "")
    }

    func testSiteVerificationRequiredWithoutAuthenticatedContextDoesNotEnterShell() async {
        let authManager = AuthManagerSpy()
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "grade",
            message: "需要重新登录"
        )
        let subject = AuthStore(authManager: authManager)

        _ = await subject.syncStateFromManager()

        XCTAssertFalse(subject.isAuthenticated)
        XCTAssertFalse(subject.shouldShowInlineSiteVerification)
        XCTAssertTrue(subject.hasPendingSiteVerification)
        XCTAssertEqual(subject.errorMessage, "需要重新登录")
        XCTAssertFalse(subject.hasAuthenticatedSessionContext)
    }

    func testAccountChoiceDefaultsToUndergraduateAndClearsStaleChallengeInputs() async {
        let authManager = AuthManagerSpy()
        authManager.loginResult = .needAccountChoice(
            SharedAccountChoiceChallenge(
                choices: [
                    SharedAccountChoice(
                        id: "postgraduate",
                        displayName: "研究生账号",
                        accountType: .postgraduate
                    ),
                    SharedAccountChoice(
                        id: "undergraduate",
                        displayName: "本科生账号",
                        accountType: .undergraduate
                    ),
                ],
                site: nil
            )
        )
        UserDefaults.standard.set(true, forKey: sessionContextKey)
        let subject = AuthStore(authManager: authManager)
        XCTAssertTrue(subject.hasAuthenticatedSessionContext)
        subject.username = "3124000000"
        subject.password = "account"
        subject.captchaCode = "abcd"
        subject.mfaCode = "123456"

        await subject.login()

        XCTAssertEqual(subject.selectedAccountChoiceId, "undergraduate")
        XCTAssertEqual(subject.captchaCode, "")
        XCTAssertEqual(subject.mfaCode, "")
        XCTAssertFalse(subject.isAuthenticated)
        XCTAssertFalse(subject.hasAuthenticatedSessionContext)
    }

    func testPreviewAutoLoginDoesNothingWithoutPreviewLaunchArgument() async {
        let authManager = AuthManagerSpy()
        let subject = AuthStore(authManager: authManager)

        await subject.previewAutoLoginIfAllowed()

        XCTAssertEqual(subject.state, .anonymous)
        XCTAssertTrue(authManager.loginRequests.isEmpty)
        XCTAssertFalse(subject.hasAuthenticatedSessionContext)
    }

    func testSetAccessModeForwardsChangesAndSkipsDuplicates() async {
        let authManager = AuthManagerSpy()
        let subject = AuthStore(authManager: authManager)
        await subject.refresh()

        await subject.setAccessMode(.normal)
        await subject.setAccessMode(.normal)
        await subject.setAccessMode(.webvpn)

        XCTAssertEqual(subject.accessMode, .webvpn)
        XCTAssertEqual(authManager.currentAccessMode, .webvpn)
        XCTAssertEqual(authManager.accessModeRequests, [.normal, .webvpn])
    }

    func testAutoSiteVerificationRequiresAuthenticatedShellContext() async {
        let authManager = AuthManagerSpy()
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "schedule",
            message: "需要补授权"
        )
        let subject = AuthStore(
            authManager: authManager,
            allowsAutomaticSiteVerification: { true }
        )

        _ = await subject.syncStateFromManager()
        await subject.autoBeginSiteVerificationIfAllowed()

        XCTAssertTrue(authManager.siteVerificationRequests.isEmpty)
    }

    func testAutoSiteVerificationRequiresExplicitOptIn() async {
        let authManager = AuthManagerSpy()
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "schedule",
            message: "需要补授权"
        )
        UserDefaults.standard.set(true, forKey: sessionContextKey)
        let subject = AuthStore(
            authManager: authManager,
            allowsAutomaticSiteVerification: { false }
        )

        _ = await subject.syncStateFromManager()
        await subject.autoBeginSiteVerificationIfAllowed()

        XCTAssertTrue(authManager.siteVerificationRequests.isEmpty)
    }

    func testAutoSiteVerificationIsRateLimitedAndWindowCapped() async {
        var now = Date(timeIntervalSince1970: 0)
        let limiter = SiteVerificationAutoLimiter(
            minimumInterval: 1,
            window: 5 * 60,
            maximumAttemptsPerWindow: 2,
            userDefaults: nil,
            now: { now }
        )
        let authManager = AuthManagerSpy()
        authManager.currentState = .siteVerificationRequired(
            username: "3124000000",
            siteName: "schedule",
            message: "需要补授权"
        )
        authManager.siteVerificationResult = .failure(message: "临时失败")
        UserDefaults.standard.set(true, forKey: sessionContextKey)
        let subject = AuthStore(
            authManager: authManager,
            autoSiteVerificationLimiter: limiter,
            allowsAutomaticSiteVerification: { true }
        )

        _ = await subject.syncStateFromManager()
        await subject.autoBeginSiteVerificationIfAllowed()
        await subject.autoBeginSiteVerificationIfAllowed()
        now = now.addingTimeInterval(1.1)
        await subject.autoBeginSiteVerificationIfAllowed()
        now = now.addingTimeInterval(1.1)
        await subject.autoBeginSiteVerificationIfAllowed()
        now = now.addingTimeInterval(5 * 60)
        await subject.autoBeginSiteVerificationIfAllowed()

        XCTAssertEqual(authManager.siteVerificationRequests, ["schedule", "schedule", "schedule"])
    }
}

private final class AuthManagerSpy: SharedAuthManaging {
    struct LoginRequest: Equatable {
        let username: String
        let password: String
    }

    var currentState: SharedAuthState = .anonymous
    var currentAccessMode: SharedAccessMode = .automatic
    var loginResult: SharedLoginResult?
    var siteVerificationResult: SharedLoginResult?
    private(set) var loginRequests: [LoginRequest] = []
    private(set) var siteVerificationRequests: [String] = []
    private(set) var logoutRequests = 0
    private(set) var accessModeRequests: [SharedAccessMode] = []

    var state: SharedAuthState { get async { currentState } }
    var accessMode: SharedAccessMode { get async { currentAccessMode } }

    func setAccessMode(_ mode: SharedAccessMode) async {
        currentAccessMode = mode
        accessModeRequests.append(mode)
    }

    func restoreSavedCredentials() async -> SharedAuthState {
        currentState
    }

    func login(username: String, password: String) async -> SharedLoginResult {
        loginRequests.append(LoginRequest(username: username, password: password))
        if let loginResult {
            if case .success(let username) = loginResult {
                currentState = .authenticated(username: username)
            }
            return loginResult
        }
        currentState = .authenticated(username: username)
        return .success(username: username)
    }

    func beginBrowserAuth(site: String?) async -> SharedLoginResult {
        let request = SharedBrowserAuthRequest(
            loginURL: "https://login.example.test",
            callbackScheme: "xjtutoolbox",
            state: "state",
            site: site
        )
        currentState = .awaitingBrowserAuth(request)
        return .needBrowserAuth(request)
    }

    func beginSiteVerification(site: String) async -> SharedLoginResult {
        siteVerificationRequests.append(site)
        if let siteVerificationResult {
            switch siteVerificationResult {
            case .success(let username):
                currentState = .authenticated(username: username)
            case .needCaptcha(let challenge):
                currentState = .awaitingCaptcha(challenge)
            case .needMfa(let maskedPhone):
                currentState = .awaitingMfa(maskedPhone: maskedPhone)
            case .needAccountChoice(let challenge):
                currentState = .awaitingAccountChoice(challenge)
            case .needBrowserAuth(let request):
                currentState = .awaitingBrowserAuth(request)
            case .failure:
                break
            }
            return siteVerificationResult
        }
        currentState = .awaitingMfa(maskedPhone: "188****0000")
        return .needMfa(maskedPhone: "188****0000")
    }

    func submitCaptcha(code: String) async -> SharedLoginResult {
        currentState = .authenticated(username: "3124000000")
        return .success(username: "3124000000")
    }

    func submitMfa(code: String) async -> SharedLoginResult {
        currentState = .authenticated(username: "3124000000")
        return .success(username: "3124000000")
    }

    func submitAccountChoice(choiceId: String) async -> SharedLoginResult {
        currentState = .authenticated(username: "3124000000")
        return .success(username: "3124000000")
    }

    func resumeBrowserAuth(callbackURL: URL) async -> SharedLoginResult {
        currentState = .authenticated(username: "3124000000")
        return .success(username: "3124000000")
    }

    func ensureSession(site: String) async throws {}

    func logout() async {
        logoutRequests += 1
        currentState = .anonymous
    }
}
