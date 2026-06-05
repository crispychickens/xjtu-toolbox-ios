import Foundation

enum SharedAccessMode: String, CaseIterable, Identifiable {
    case automatic
    case normal
    case webvpn

    var id: String { rawValue }
}

enum SharedAuthState: Equatable {
    case anonymous
    case authenticating(username: String)
    case awaitingCaptcha(SharedCaptchaChallenge)
    case awaitingMfa(maskedPhone: String)
    case awaitingAccountChoice(SharedAccountChoiceChallenge)
    case awaitingBrowserAuth(SharedBrowserAuthRequest)
    case authenticated(username: String)
    case siteVerificationRequired(username: String, siteName: String, message: String)
    case passwordInvalidated(siteName: String)
}

struct SharedCaptchaChallenge: Equatable {
    let imageBase64: String
    let site: String?
}

enum SharedAccountType: String, Equatable {
    case undergraduate
    case postgraduate
    case unknown
}

struct SharedAccountChoice: Identifiable, Equatable {
    let id: String
    let displayName: String
    let accountType: SharedAccountType
}

struct SharedAccountChoiceChallenge: Equatable {
    let choices: [SharedAccountChoice]
    let site: String?
}

struct SharedBrowserAuthRequest: Equatable {
    let loginURL: String
    let callbackScheme: String
    let state: String
    let site: String?
}

enum SharedLoginResult: Equatable {
    case success(username: String)
    case needCaptcha(SharedCaptchaChallenge)
    case needMfa(maskedPhone: String)
    case needAccountChoice(SharedAccountChoiceChallenge)
    case needBrowserAuth(SharedBrowserAuthRequest)
    case failure(message: String)
}

protocol SharedAuthManaging {
    var state: SharedAuthState { get async }
    var accessMode: SharedAccessMode { get async }

    func setAccessMode(_ mode: SharedAccessMode) async
    func restoreSavedCredentials() async -> SharedAuthState
    func login(username: String, password: String) async -> SharedLoginResult
    func beginBrowserAuth(site: String?) async -> SharedLoginResult
    func beginSiteVerification(site: String) async -> SharedLoginResult
    func submitCaptcha(code: String) async -> SharedLoginResult
    func submitMfa(code: String) async -> SharedLoginResult
    func submitAccountChoice(choiceId: String) async -> SharedLoginResult
    func resumeBrowserAuth(callbackURL: URL) async -> SharedLoginResult
    func ensureSession(site: String) async throws
    func logout() async
}

final class PreviewAuthManager: SharedAuthManaging {
    private var currentState: SharedAuthState = .anonymous
    private let accessModeStore: AccessModeStoring
    private var pendingUsername: String = ""

    var state: SharedAuthState { get async { currentState } }
    var accessMode: SharedAccessMode { get async { accessModeStore.load() } }

    init(accessModeStore: AccessModeStoring = UserDefaultsAccessModeStore()) {
        self.accessModeStore = accessModeStore
    }

    func setAccessMode(_ mode: SharedAccessMode) async {
        accessModeStore.save(mode)
    }

    func restoreSavedCredentials() async -> SharedAuthState {
        currentState
    }

    func login(username: String, password: String) async -> SharedLoginResult {
        pendingUsername = username
        if password == "mfa" {
            currentState = .awaitingMfa(maskedPhone: "188****0000")
            return .needMfa(maskedPhone: "188****0000")
        }
        if password == "captcha" {
            let challenge = SharedCaptchaChallenge(
                imageBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lzqY2QAAAABJRU5ErkJggg==",
                site: nil
            )
            currentState = .awaitingCaptcha(challenge)
            return .needCaptcha(challenge)
        }
        if password == "account" {
            let challenge = SharedAccountChoiceChallenge(
                choices: [
                    SharedAccountChoice(id: "undergraduate-preview", displayName: "本科生账号", accountType: .undergraduate),
                    SharedAccountChoice(id: "postgraduate-preview", displayName: "研究生账号", accountType: .postgraduate),
                ],
                site: nil
            )
            currentState = .awaitingAccountChoice(challenge)
            return .needAccountChoice(challenge)
        }
        if password == "browser" {
            return await beginBrowserAuth(site: nil)
        }
        if password.isEmpty {
            currentState = .anonymous
            return .failure(message: "请输入密码")
        }
        currentState = .authenticated(username: username)
        return .success(username: username)
    }

    func beginBrowserAuth(site: String?) async -> SharedLoginResult {
        let request = SharedBrowserAuthRequest(
            loginURL: "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
            callbackScheme: "xjtutoolbox",
            state: "preview-state",
            site: site
        )
        currentState = .awaitingBrowserAuth(request)
        return .needBrowserAuth(request)
    }

    func beginSiteVerification(site: String) async -> SharedLoginResult {
        currentState = .awaitingMfa(maskedPhone: "188****0000")
        return .needMfa(maskedPhone: "188****0000")
    }

    func submitCaptcha(code: String) async -> SharedLoginResult {
        guard code.count >= 4 else {
            return .failure(message: "请输入图形验证码")
        }
        currentState = .authenticated(username: pendingUsername)
        return .success(username: pendingUsername)
    }

    func submitMfa(code: String) async -> SharedLoginResult {
        guard code.count == 6 else {
            return .failure(message: "请输入 6 位验证码")
        }
        currentState = .authenticated(username: pendingUsername)
        return .success(username: pendingUsername)
    }

    func submitAccountChoice(choiceId: String) async -> SharedLoginResult {
        guard !choiceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .failure(message: "请选择账号类型")
        }
        currentState = .authenticated(username: pendingUsername)
        return .success(username: pendingUsername)
    }

    func resumeBrowserAuth(callbackURL: URL) async -> SharedLoginResult {
        guard case .awaitingBrowserAuth(let request) = currentState else {
            return .failure(message: "没有正在进行的官方网页登录")
        }
        guard callbackURL.scheme?.localizedCaseInsensitiveCompare(request.callbackScheme) == .orderedSame else {
            return .failure(message: "网页登录回跳来源不匹配")
        }
        let items = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let state = items.first { $0.name == "state" }?.value
        let ticket = items.first { $0.name == "ticket" }?.value
        let code = items.first { $0.name == "code" }?.value
        guard state == request.state else {
            return .failure(message: "网页登录回跳状态不匹配")
        }
        guard ticket?.isEmpty == false || code?.isEmpty == false else {
            return .failure(message: "网页登录回跳缺少 ticket 或 code")
        }
        let username = pendingUsername.isEmpty ? "3124000000" : pendingUsername
        currentState = .authenticated(username: username)
        return .success(username: username)
    }

    func ensureSession(site: String) async throws {}

    func logout() async {
        currentState = .anonymous
    }
}
