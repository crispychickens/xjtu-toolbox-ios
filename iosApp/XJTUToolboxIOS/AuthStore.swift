import Foundation

@MainActor
final class AuthStore: ObservableObject {
    private enum PersistedKeys {
        static let hasAuthenticatedSessionContext = "auth.hasAuthenticatedSessionContext"
    }

    @Published private(set) var state: SharedAuthState = .anonymous
    @Published var username: String = ""
    @Published var password: String = ""
    @Published var captchaCode: String = ""
    @Published var mfaCode: String = ""
    @Published var selectedAccountChoiceId: String = ""
    @Published var errorMessage: String?
    @Published private(set) var browserAuthRequest: SharedBrowserAuthRequest?
    @Published var accessMode: SharedAccessMode = .automatic
    @Published var isBusy = false
    @Published private(set) var hasAuthenticatedSessionContext = false

    private let authManager: SharedAuthManaging
    private let autoSiteVerificationLimiter: SiteVerificationAutoLimiter
    private let allowsAutomaticSiteVerification: () -> Bool
    private var didApplyFreshLoginRequirement = false

    init(
        authManager: SharedAuthManaging,
        autoSiteVerificationLimiter: SiteVerificationAutoLimiter = SiteVerificationAutoLimiter(),
        allowsAutomaticSiteVerification: @escaping () -> Bool = {
            XjtuLaunchArguments.shouldAutoBeginSiteVerification
        }
    ) {
        self.authManager = authManager
        self.autoSiteVerificationLimiter = autoSiteVerificationLimiter
        self.allowsAutomaticSiteVerification = allowsAutomaticSiteVerification
        hasAuthenticatedSessionContext = UserDefaults.standard.bool(
            forKey: PersistedKeys.hasAuthenticatedSessionContext
        )
    }

    var isAuthenticated: Bool {
        if case .authenticated = state { return true }
        if case .siteVerificationRequired = state { return hasAuthenticatedSessionContext }
        return false
    }

    var shouldShowInlineSiteVerification: Bool {
        guard case .siteVerificationRequired = state else { return false }
        return hasAuthenticatedSessionContext
    }

    var hasPendingSiteVerification: Bool {
        if case .siteVerificationRequired = state { return true }
        return false
    }

    var displayUsername: String? {
        switch state {
        case .authenticated(let username),
             .siteVerificationRequired(let username, _, _),
             .authenticating(let username):
            return username
        default:
            return username.isEmpty ? nil : username
        }
    }

    func refresh() async {
        if XjtuLaunchArguments.shouldRequireFreshLogin, !didApplyFreshLoginRequirement {
            didApplyFreshLoginRequirement = true
            await authManager.logout()
            username = ""
            password = ""
            captchaCode = ""
            mfaCode = ""
            selectedAccountChoiceId = ""
            errorMessage = nil
            applyState(.anonymous)
        } else {
            applyState(await authManager.restoreSavedCredentials())
        }
        accessMode = await authManager.accessMode
        if username.isEmpty, let prefilledUsername = XjtuLaunchArguments.prefilledUsername {
            username = prefilledUsername
        }
    }

    @discardableResult
    func syncStateFromManager() async -> SharedAuthState {
        let managerState = await authManager.state
        applyState(managerState)
        accessMode = await authManager.accessMode
        return managerState
    }

    func setAccessMode(_ mode: SharedAccessMode) async {
        guard accessMode != mode else { return }
        accessMode = mode
        await authManager.setAccessMode(mode)
    }

    func login() async {
        guard !isBusy else { return }
        guard !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "请输入学号"
            return
        }
        autoSiteVerificationLimiter.reset()
        clearAuthenticatedSessionContext()
        isBusy = true
        errorMessage = nil
        state = .authenticating(username: username)
        let result = await authManager.login(username: username, password: password)
        await apply(result)
        isBusy = false
    }

    func beginBrowserAuth() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.beginBrowserAuth(site: nil)
        await apply(result)
        isBusy = false
    }

    func beginSiteVerification() async {
        guard !isBusy else { return }
        guard case .siteVerificationRequired(_, let siteName, _) = state else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.beginSiteVerification(site: siteName)
        await apply(result)
        isBusy = false
    }

    func autoBeginSiteVerificationIfAllowed() async {
        guard allowsAutomaticSiteVerification() else { return }
        guard !isBusy else { return }
        guard shouldShowInlineSiteVerification,
              case .siteVerificationRequired(_, let siteName, _) = state else {
            return
        }
        guard autoSiteVerificationLimiter.recordAttemptIfAllowed(siteName: siteName) == .allowed else {
            #if DEBUG
            RealFeatureValidationRunner.recordAutoSiteVerification(
                siteName: siteName,
                phase: "rateLimited",
                state: state,
                errorPresent: errorMessage != nil
            )
            #endif
            return
        }
        #if DEBUG
        RealFeatureValidationRunner.recordAutoSiteVerification(
            siteName: siteName,
            phase: "allowed",
            state: state,
            errorPresent: errorMessage != nil
        )
        #endif
        await beginSiteVerification()
        #if DEBUG
        RealFeatureValidationRunner.recordAutoSiteVerification(
            siteName: siteName,
            phase: "finished",
            state: state,
            errorPresent: errorMessage != nil
        )
        #endif
    }

    func submitCaptcha() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.submitCaptcha(code: captchaCode)
        await apply(result)
        isBusy = false
    }

    func submitMfa() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.submitMfa(code: mfaCode)
        await apply(result)
        isBusy = false
    }

    func submitAccountChoice() async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.submitAccountChoice(choiceId: selectedAccountChoiceId)
        await apply(result)
        isBusy = false
    }

    func resumeBrowserAuth(callbackURL: URL) async {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        let result = await authManager.resumeBrowserAuth(callbackURL: callbackURL)
        await apply(result)
        isBusy = false
    }

    func logout() async {
        await authManager.logout()
        autoSiteVerificationLimiter.reset()
        username = ""
        password = ""
        captchaCode = ""
        mfaCode = ""
        selectedAccountChoiceId = ""
        errorMessage = nil
        applyState(.anonymous)
    }

    func previewAutoLoginIfAllowed() async {
        guard XjtuLaunchArguments.allowsPreviewAutoLogin else { return }
        guard case .anonymous = state else { return }
        username = "3124000000"
        password = "preview"
        await login()
    }

    func previewAccountChoiceIfAllowed() async {
        guard XjtuLaunchArguments.allowsPreviewAccountChoice else { return }
        guard case .anonymous = state else { return }
        username = "3124000000"
        password = "account"
        await login()
    }

    private func apply(_ result: SharedLoginResult) async {
        switch result {
        case .success(let username):
            applyState(.authenticated(username: username))
        case .needCaptcha(let challenge):
            applyState(.awaitingCaptcha(challenge))
        case .needMfa(let maskedPhone):
            applyState(.awaitingMfa(maskedPhone: maskedPhone))
        case .needAccountChoice(let challenge):
            applyState(.awaitingAccountChoice(challenge))
        case .needBrowserAuth(let request):
            applyState(.awaitingBrowserAuth(request))
        case .failure(let message):
            let managerState = await authManager.state
            applyState(managerState)
            if case .siteVerificationRequired = managerState {
                errorMessage = siteVerificationFailureMessage(message)
            } else {
                errorMessage = message
            }
        }
    }

    private func applyState(_ newState: SharedAuthState) {
        state = newState
        switch newState {
        case .awaitingCaptcha:
            mfaCode = ""
            selectedAccountChoiceId = ""
            browserAuthRequest = nil
        case .awaitingMfa:
            captchaCode = ""
            selectedAccountChoiceId = ""
            browserAuthRequest = nil
        case .awaitingAccountChoice(let challenge):
            captchaCode = ""
            mfaCode = ""
            selectedAccountChoiceId = preferredAccountChoiceId(in: challenge)
            browserAuthRequest = nil
        case .awaitingBrowserAuth(let request):
            captchaCode = ""
            mfaCode = ""
            selectedAccountChoiceId = ""
            browserAuthRequest = request
        case .authenticated(let username):
            hasAuthenticatedSessionContext = true
            UserDefaults.standard.set(true, forKey: PersistedKeys.hasAuthenticatedSessionContext)
            if self.username.isEmpty {
                self.username = username
            }
            browserAuthRequest = nil
        case .siteVerificationRequired(let username, _, let message):
            if self.username.isEmpty {
                self.username = username
            }
            password = ""
            captchaCode = ""
            mfaCode = ""
            selectedAccountChoiceId = ""
            errorMessage = hasAuthenticatedSessionContext ? nil : message
            browserAuthRequest = nil
        case .anonymous, .passwordInvalidated:
            clearAuthenticatedSessionContext()
            browserAuthRequest = nil
        default:
            browserAuthRequest = nil
        }
    }

    private func clearAuthenticatedSessionContext() {
        hasAuthenticatedSessionContext = false
        UserDefaults.standard.removeObject(forKey: PersistedKeys.hasAuthenticatedSessionContext)
    }

    private func preferredAccountChoiceId(in challenge: SharedAccountChoiceChallenge) -> String {
        if let undergraduate = challenge.choices.first(where: { $0.accountType == .undergraduate }) {
            return undergraduate.id
        }
        return challenge.choices.first?.id ?? ""
    }

    private func siteVerificationFailureMessage(_ message: String) -> String {
        if message.contains("CAS 仍返回登录表单") {
            return "补授权暂未完成。请稍后重试；如果仍失败，请重新输入统一身份认证密码登录。"
        }
        return message
    }
}

final class SiteVerificationAutoLimiter {
    enum Decision: Equatable {
        case allowed
        case rejected
    }

    private let minimumInterval: TimeInterval
    private let window: TimeInterval
    private let maximumAttemptsPerWindow: Int
    private let now: () -> Date
    private let userDefaults: UserDefaults?
    private let storageKey: String
    private var attemptsBySite: [String: [Date]]

    init(
        minimumInterval: TimeInterval = 1,
        window: TimeInterval = 5 * 60,
        maximumAttemptsPerWindow: Int = 1,
        userDefaults: UserDefaults? = .standard,
        storageKey: String = "auth.autoSiteVerificationAttemptTimes",
        now: @escaping () -> Date = Date.init
    ) {
        self.minimumInterval = max(0, minimumInterval)
        self.window = max(1, window)
        self.maximumAttemptsPerWindow = max(1, maximumAttemptsPerWindow)
        self.userDefaults = userDefaults
        self.storageKey = storageKey
        self.now = now
        attemptsBySite = (userDefaults?.dictionary(forKey: storageKey) ?? [:]).reduce(into: [:]) {
            result, entry in
            guard let timestamps = entry.value as? [TimeInterval] else { return }
            result[entry.key] = timestamps.map { Date(timeIntervalSince1970: $0) }
        }
    }

    func recordAttemptIfAllowed(siteName: String) -> Decision {
        let current = now()
        pruneAttempts(before: current)

        if let latest = attemptsBySite.values.flatMap({ $0 }).max(),
           current.timeIntervalSince(latest) < minimumInterval {
            return .rejected
        }

        var siteAttempts = attemptsBySite[siteName] ?? []
        guard siteAttempts.count < maximumAttemptsPerWindow else {
            return .rejected
        }

        siteAttempts.append(current)
        attemptsBySite[siteName] = siteAttempts
        persistAttempts()
        return .allowed
    }

    func reset() {
        attemptsBySite = [:]
        userDefaults?.removeObject(forKey: storageKey)
    }

    private func pruneAttempts(before date: Date) {
        let pruned = attemptsBySite.reduce(into: [String: [Date]]()) { result, entry in
            let attempts = entry.value.filter { date.timeIntervalSince($0) < window }
            if !attempts.isEmpty {
                result[entry.key] = attempts
            }
        }
        guard pruned != attemptsBySite else { return }
        attemptsBySite = pruned
        persistAttempts()
    }

    private func persistAttempts() {
        userDefaults?.set(
            attemptsBySite.mapValues { $0.map(\.timeIntervalSince1970) },
            forKey: storageKey
        )
    }
}
