import Foundation

#if canImport(XJTUToolboxShared)
import Security
import XJTUToolboxShared

enum KmpPlatformAdapterError: LocalizedError {
    case badURL(String)

    var errorDescription: String? {
        switch self {
        case .badURL(let url):
            return "无效 URL：\(url)"
        }
    }
}

final class KmpRsaPasswordEncryptor: XJTUToolboxShared.PasswordEncryptor {
    func encrypt(
        password: String,
        publicKey: String,
        completionHandler: @escaping @Sendable (PasswordEncryptionResult?, Error?) -> Void
    ) {
        completionHandler(Self.encrypt(password: password, publicKey: publicKey), nil)
    }

    private static func encrypt(password: String, publicKey: String) -> PasswordEncryptionResult {
        guard let keyData = normalizedKeyData(publicKey) else {
            return PasswordEncryptionResultFailure(message: "RSA 公钥格式无效")
        }
        guard let secKey = makeSecKey(from: keyData) ?? makeSecKey(from: wrapPkcs1PublicKeyAsX509(keyData)) else {
            return PasswordEncryptionResultFailure(message: "RSA 公钥无法被 iOS Security 解析")
        }
        guard SecKeyIsAlgorithmSupported(secKey, .encrypt, .rsaEncryptionPKCS1) else {
            return PasswordEncryptionResultFailure(message: "当前平台不支持 RSA/PKCS1 加密")
        }
        guard let plainData = password.data(using: .utf8) else {
            return PasswordEncryptionResultFailure(message: "密码编码失败")
        }

        var error: Unmanaged<CFError>?
        guard let encrypted = SecKeyCreateEncryptedData(
            secKey,
            .rsaEncryptionPKCS1,
            plainData as CFData,
            &error
        ) as Data? else {
            let message = error?.takeRetainedValue().localizedDescription ?? "RSA 加密失败"
            return PasswordEncryptionResultFailure(message: message)
        }

        return PasswordEncryptionResultSuccess(encryptedPassword: "__RSA__\(encrypted.base64EncodedString())")
    }

    private static func normalizedKeyData(_ publicKey: String) -> Data? {
        let base64 = publicKey
            .replacingOccurrences(of: "-----BEGIN PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----END PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----BEGIN RSA PUBLIC KEY-----", with: "")
            .replacingOccurrences(of: "-----END RSA PUBLIC KEY-----", with: "")
            .filter { !$0.isWhitespace }
        return Data(base64Encoded: String(base64), options: [.ignoreUnknownCharacters])
    }

    private static func makeSecKey(from data: Data) -> SecKey? {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: data.count * 8,
        ]
        var error: Unmanaged<CFError>?
        return SecKeyCreateWithData(data as CFData, attributes as CFDictionary, &error)
    }

    private static func wrapPkcs1PublicKeyAsX509(_ pkcs1: Data) -> Data {
        let rsaAlgorithmIdentifier = Data([
            0x30, 0x0d,
            0x06, 0x09,
            0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00,
        ])
        let bitString = derTagged(0x03, Data([0x00]) + pkcs1)
        return derTagged(0x30, rsaAlgorithmIdentifier + bitString)
    }

    private static func derTagged(_ tag: UInt8, _ content: Data) -> Data {
        Data([tag]) + derLength(content.count) + content
    }

    private static func derLength(_ length: Int) -> Data {
        if length < 0x80 {
            return Data([UInt8(length)])
        }
        var bytes: [UInt8] = []
        var value = length
        while value > 0 {
            bytes.insert(UInt8(value & 0xff), at: 0)
            value >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)]) + Data(bytes)
    }
}

final class KmpUserDefaultsVisitorIdProvider: XJTUToolboxShared.VisitorIdProvider {
    private let defaults: UserDefaults
    private let key: String

    init(
        defaults: UserDefaults = .standard,
        key: String = "com.xjtu.toolbox.ios.auth.fpVisitorId"
    ) {
        self.defaults = defaults
        self.key = key
    }

    func visitorId() -> String {
        if let existing = defaults.string(forKey: key), !existing.isEmpty {
            return existing
        }
        let generated = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(generated, forKey: key)
        return generated
    }
}

final class KmpUserDefaultsAccessModeStore: XJTUToolboxShared.AccessModeStore {
    private let store: AccessModeStoring

    init(store: AccessModeStoring = UserDefaultsAccessModeStore()) {
        self.store = store
    }

    func load() -> AccessMode {
        store.load().kmpValue
    }

    func save(mode: AccessMode) {
        store.save(mapAccessMode(mode))
    }
}

final class KmpKeychainCredentialVault: XJTUToolboxShared.CredentialVault {
    private let store: CredentialStoring

    init(store: CredentialStoring = KeychainCredentialStore()) {
        self.store = store
    }

    func load(completionHandler: @escaping @Sendable (Credentials?, Error?) -> Void) {
        do {
            let saved = try store.load()
            completionHandler(saved.map { Credentials(username: $0.username, password: $0.password) }, nil)
        } catch {
            completionHandler(nil, error)
        }
    }

    func save(credentials: Credentials, completionHandler: @escaping @Sendable (Error?) -> Void) {
        do {
            try store.save(
                SavedCredentials(
                    username: credentials.username,
                    password: credentials.password
                )
            )
            completionHandler(nil)
        } catch {
            completionHandler(error)
        }
    }

    func clear(completionHandler: @escaping @Sendable (Error?) -> Void) {
        do {
            try store.clear()
            completionHandler(nil)
        } catch {
            completionHandler(error)
        }
    }
}

final class KmpUserDefaultsLoginAttemptStore: XJTUToolboxShared.LoginAttemptStore {
    private let store: LoginAttemptStoring

    init(store: LoginAttemptStoring = UserDefaultsLoginAttemptStore()) {
        self.store = store
    }

    func load(key: String) -> LoginAttemptRecord? {
        guard let saved = store.load(key: key) else {
            return nil
        }
        return LoginAttemptRecord(
            transientFailureCount: Int32(saved.transientFailureCount),
            retryAtMillis: saved.retryAtMillis.map { KotlinLong(value: $0) }
        )
    }

    func save(key: String, record: LoginAttemptRecord) {
        store.save(
            SavedLoginAttempt(
                transientFailureCount: Int(record.transientFailureCount),
                retryAtMillis: record.retryAtMillis?.int64Value
            ),
            key: key
        )
    }

    func remove(key: String) {
        store.remove(key: key)
    }

    func clear() {
        store.clear()
    }
}

final class KmpHTTPCookieStore: XJTUToolboxShared.CookieStore {
    private let storage: HTTPCookieStorage

    init(storage: HTTPCookieStorage = .shared) {
        self.storage = storage
    }

    func save(cookie: StoredCookie) {
        var properties: [HTTPCookiePropertyKey: Any] = [
            .name: cookie.name,
            .value: cookie.value,
            .domain: cookie.domain,
            .path: cookie.path,
        ]
        if cookie.secure {
            properties[.secure] = "TRUE"
        }
        if cookie.httpOnly {
            properties[HTTPCookiePropertyKey("HttpOnly")] = "TRUE"
        }
        if let expiresAt = cookie.expiresAtEpochMillis?.int64Value {
            properties[.expires] = Date(timeIntervalSince1970: Double(expiresAt) / 1_000.0)
        }
        if let httpCookie = HTTPCookie(properties: properties) {
            storage.setCookie(httpCookie)
        }
    }

    func loadForHost(host: String) -> [StoredCookie] {
        (storage.cookies ?? [])
            .filter { cookie in domain(cookie.domain, matches: host) }
            .map { cookie in
                StoredCookie(
                    name: cookie.name,
                    value: cookie.value,
                    domain: cookie.domain,
                    path: cookie.path,
                    expiresAtEpochMillis: cookie.expiresDate.map {
                        KotlinLong(value: Int64($0.timeIntervalSince1970 * 1_000))
                    },
                    secure: cookie.isSecure,
                    httpOnly: cookie.isHTTPOnly
                )
            }
    }

    func clear() {
        for cookie in storage.cookies ?? [] {
            storage.deleteCookie(cookie)
        }
    }

    private func domain(_ cookieDomain: String, matches host: String) -> Bool {
        let normalizedDomain = cookieDomain.trimmingCharacters(in: CharacterSet(charactersIn: ".")).lowercased()
        let normalizedHost = host.lowercased()
        return normalizedHost == normalizedDomain || normalizedHost.hasSuffix(".\(normalizedDomain)")
    }
}

final class KmpURLSessionHttpClient: XJTUToolboxShared.HttpClient {
    private let session: URLSession
    private let diagnosticCookieStorage: HTTPCookieStorage?

    init(
        session: URLSession = KmpURLSessionHttpClient.defaultSession(),
        diagnosticCookieStorage: HTTPCookieStorage? = nil
    ) {
        self.session = session
        self.diagnosticCookieStorage = diagnosticCookieStorage
    }

    func execute(
        request: HttpRequest,
        completionHandler: @escaping @Sendable (HttpResponse?, Error?) -> Void
    ) {
        guard let url = URL(string: request.url) else {
            completionHandler(nil, KmpPlatformAdapterError.badURL(request.url))
            return
        }

        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method.name
        for (field, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: field)
        }
        if let body = request.body {
            urlRequest.httpBody = Data(kotlinByteArray: body)
        }

        AuthNetworkDebugLog.request(
            request,
            actualHeaderNames: urlRequest.allHTTPHeaderFields?.keys.sorted() ?? [],
            actualCookieNames: urlRequest.actualCookieNames
        )
        session.dataTask(with: urlRequest) { data, response, error in
            if let error {
                AuthNetworkDebugLog.error(request: request, error: error)
                completionHandler(nil, error)
                return
            }

            let httpResponse = response as? HTTPURLResponse
            let bodyData = data ?? Data()
            let bodyText = String(data: bodyData, encoding: .utf8) ?? bodyData.base64EncodedString()
            AuthNetworkDebugLog.response(
                request: request,
                response: httpResponse,
                bodyText: bodyText,
                cookieStorage: self.diagnosticCookieStorage
            )
            completionHandler(
                HttpResponse(
                    code: Int32(httpResponse?.statusCode ?? -1),
                    finalUrl: httpResponse?.redirectLocationURL?.absoluteString ?? httpResponse?.url?.absoluteString ?? request.url,
                    headers: httpResponse?.stringHeaders ?? [:],
                    bodyText: bodyText
                ),
                nil
            )
        }.resume()
    }
}

enum KmpSessionBackendFactory {
    static func normal(
        session: URLSession? = nil,
        cookieStorage: HTTPCookieStorage = .shared,
        minimumRequestIntervalMillis: Int64 = 0
    ) -> SessionBackend {
        let httpClient = pace(
            KmpURLSessionHttpClient(
                session: session ?? KmpURLSessionHttpClient.defaultSession(cookieStorage: cookieStorage),
                diagnosticCookieStorage: cookieStorage
            ),
            minimumRequestIntervalMillis: minimumRequestIntervalMillis
        )
        return SessionBackend.companion.normal(
            httpClient: httpClient,
            cookieStore: KmpHTTPCookieStore(storage: cookieStorage)
        )
    }

    static func webvpn(
        session: URLSession? = nil,
        cookieStorage: HTTPCookieStorage = .shared,
        minimumRequestIntervalMillis: Int64 = 0
    ) -> SessionBackend {
        let httpClient = pace(
            KmpURLSessionHttpClient(
                session: session ?? KmpURLSessionHttpClient.defaultSession(cookieStorage: cookieStorage),
                diagnosticCookieStorage: cookieStorage
            ),
            minimumRequestIntervalMillis: minimumRequestIntervalMillis
        )
        return SessionBackend.companion.webvpn(
            httpClient: httpClient,
            cookieStore: KmpHTTPCookieStore(storage: cookieStorage)
        )
    }

    private static func pace(
        _ httpClient: XJTUToolboxShared.HttpClient,
        minimumRequestIntervalMillis: Int64
    ) -> XJTUToolboxShared.HttpClient {
        RequestPacingHttpClientFactory.shared.wrap(
            delegate: httpClient,
            minimumIntervalMillis: minimumRequestIntervalMillis
        )
    }
}

final class KmpPreviewAuthEngine: XJTUToolboxShared.AuthEngine {
    private var pendingUsername: String?

    func login(
        credentials: Credentials,
        accessMode: AccessMode,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        pendingUsername = credentials.username
        if credentials.password == "mfa" {
            completionHandler(
                EngineLoginResultNeedMfa(
                    challenge: MfaChallenge(
                        flow: MfaFlow.mfaDetect,
                        maskedPhone: "188****0000",
                        site: nil
                    )
                ),
                nil
            )
        } else if credentials.password == "captcha" {
            completionHandler(
                EngineLoginResultNeedCaptcha(
                    challenge: CaptchaChallenge(
                        imageBase64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lzqY2QAAAABJRU5ErkJggg==",
                        site: nil
                    )
                ),
                nil
            )
        } else if credentials.password == "account" {
            completionHandler(
                EngineLoginResultNeedAccountChoice(
                    challenge: AccountChoiceChallenge(
                        choices: [
                            AccountChoice(
                                id: "undergraduate-preview",
                                displayName: "本科生账号",
                                accountType: AccountType.undergraduate
                            ),
                            AccountChoice(
                                id: "postgraduate-preview",
                                displayName: "研究生账号",
                                accountType: AccountType.postgraduate
                            ),
                        ],
                        site: nil
                    )
                ),
                nil
            )
        } else if credentials.password == "browser" {
            beginBrowserAuth(site: nil, completionHandler: completionHandler)
        } else if credentials.password.isEmpty {
            completionHandler(EngineLoginResultInvalidPassword(siteName: "CAS"), nil)
        } else {
            completionHandler(EngineLoginResultSuccess(username: credentials.username), nil)
        }
    }

    func beginBrowserAuth(
        site: SiteKey?,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        completionHandler(
            EngineLoginResultNeedBrowserAuth(
                challenge: BrowserAuthChallenge(
                    loginUrl: "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
                    callbackScheme: "xjtutoolbox",
                    state: "preview-state",
                    site: site
                )
            ),
            nil
        )
    }

    func beginSiteVerification(
        credentials: Credentials,
        accessMode: AccessMode,
        site: SiteKey,
        context: SiteVerificationContext,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        pendingUsername = credentials.username
        completionHandler(
            EngineLoginResultNeedMfa(
                challenge: MfaChallenge(
                    flow: MfaFlow.safetyVerify,
                    maskedPhone: "188****0000",
                    site: site
                )
            ),
            nil
        )
    }

    func submitCaptcha(
        code: String,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        if code.count >= 4 {
            completionHandler(EngineLoginResultSuccess(username: pendingUsername ?? "3124000000"), nil)
        } else {
            completionHandler(EngineLoginResultVerificationRejected(message: "请输入图形验证码"), nil)
        }
    }

    func submitMfa(
        code: String,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        if code.count == 6 {
            completionHandler(EngineLoginResultSuccess(username: pendingUsername ?? "3124000000"), nil)
        } else {
            completionHandler(EngineLoginResultVerificationRejected(message: "请输入 6 位验证码"), nil)
        }
    }

    func submitAccountChoice(
        choiceId: String,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        if choiceId.isEmpty {
            completionHandler(EngineLoginResultVerificationRejected(message: "请选择账号类型"), nil)
        } else {
            completionHandler(EngineLoginResultSuccess(username: pendingUsername ?? "3124000000"), nil)
        }
    }

    func resumeBrowserAuth(
        callback: BrowserAuthCallback,
        completionHandler: @escaping @Sendable (EngineLoginResult?, Error?) -> Void
    ) {
        if callback.ticket?.isEmpty == false || callback.code?.isEmpty == false {
            completionHandler(EngineLoginResultSuccess(username: pendingUsername ?? "3124000000"), nil)
        } else {
            completionHandler(EngineLoginResultVerificationRejected(message: "网页登录回跳缺少 ticket 或 code"), nil)
        }
    }

    func logout(completionHandler: @escaping @Sendable (Error?) -> Void) {
        pendingUsername = nil
        completionHandler(nil)
    }
}

private extension HTTPURLResponse {
    var stringHeaders: [String: String] {
        allHeaderFields.reduce(into: [String: String]()) { result, entry in
            guard let field = entry.key as? String else { return }
            result[field] = String(describing: entry.value)
        }
    }

    var redirectLocationURL: URL? {
        guard (300...399).contains(statusCode),
              let location = stringHeaders.first(where: { $0.key.equalsIgnoringCase("Location") })?.value,
              let base = url
        else {
            return nil
        }
        return URL(string: location, relativeTo: base)?.absoluteURL
    }
}

private final class CasLoginRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        if task.originalRequest?.isCasLoginPost == true ||
            (task.originalRequest?.isMobileJwappAuthorizeGet == true && request.isCasCallbackAuthorizeGet == true) ||
            (task.originalRequest?.isCasCallbackAuthorizeGet == true && request.isInsecureMobileJwappCallback == true) ||
            (task.originalRequest?.isCasOauthAuthorizeGet == true && request.isInsecureMobileJwappCallback == true) ||
            task.originalRequest?.isSecureMobileJwappCallback == true {
            completionHandler(nil)
            return
        }
        var redirectedRequest = request
        redirectedRequest.setValue(nil, forHTTPHeaderField: "Cookie")
        completionHandler(redirectedRequest)
    }
}

private enum AuthNetworkDebugLog {
    private static let prefix = "[DEBUG-AUTH-2FA]"

    static func request(
        _ request: HttpRequest,
        actualHeaderNames: [String] = [],
        actualCookieNames: [String] = []
    ) {
        guard XjtuLaunchArguments.shouldLogAuthNetworkDebug else { return }
        NSLog(
            "%@ request %@ %@ bodyFields=%@ headerNames=%@ actualHeaderNames=%@ actualCookieNames=%@",
            prefix,
            request.method.name,
            sanitizedURL(request.url),
            bodyFields(request.body),
            request.headers.keys.sorted().joined(separator: ","),
            actualHeaderNames.joined(separator: ","),
            actualCookieNames.joined(separator: ",")
        )
    }

    static func response(
        request: HttpRequest,
        response: HTTPURLResponse?,
        bodyText: String,
        cookieStorage: HTTPCookieStorage?
    ) {
        guard XjtuLaunchArguments.shouldLogAuthNetworkDebug else { return }
        let cookieNames = (cookieStorage?.cookies ?? [])
            .map(\.name)
            .sorted()
            .joined(separator: ",")
        NSLog(
            "%@ response %@ %@ -> code=%d final=%@ redirect=%@ redirectKeys=%@ redirectHasToken=%@ body=%@ responseHeaderNames=%@ cookieNames=%@",
            prefix,
            request.method.name,
            sanitizedURL(request.url),
            response?.statusCode ?? -1,
            sanitizedURL(response?.url?.absoluteString ?? request.url),
            sanitizedURL(redirectLocation(response) ?? ""),
            parameterNames(redirectLocation(response)),
            containsParameter(redirectLocation(response), named: "token") ? "true" : "false",
            classify(bodyText),
            response?.allHeaderFields.keys.compactMap { $0 as? String }.sorted().joined(separator: ",") ?? "",
            cookieNames
        )
    }

    static func error(request: HttpRequest, error: Error) {
        guard XjtuLaunchArguments.shouldLogAuthNetworkDebug else { return }
        NSLog(
            "%@ error %@ %@ message=%@",
            prefix,
            request.method.name,
            sanitizedURL(request.url),
            error.localizedDescription
        )
    }

    private static func sanitizedURL(_ raw: String) -> String {
        if raw.isEmpty { return "" }
        guard let components = URLComponents(string: raw) else { return "<bad-url>" }
        return "\(components.host ?? "<no-host>")\(components.path)"
    }

    private static func redirectLocation(_ response: HTTPURLResponse?) -> String? {
        response?.allHeaderFields.first { key, _ in
            (key as? String)?.caseInsensitiveCompare("Location") == .orderedSame
        }?.value as? String
    }

    private static func parameterNames(_ raw: String?) -> String {
        parameterNamesFromRawURL(raw).sorted().joined(separator: ",")
    }

    private static func containsParameter(_ raw: String?, named target: String) -> Bool {
        parameterNamesFromRawURL(raw).contains { $0.equalsIgnoringCase(target) }
    }

    private static func parameterNamesFromRawURL(_ raw: String?) -> [String] {
        guard let raw, !raw.isEmpty else { return [] }
        var names: [String] = []
        if let components = URLComponents(string: raw) {
            names += components.queryItems?.map(\.name) ?? []
            if let fragment = components.fragment {
                names += parameterNamesFromFragment(fragment)
            }
        }
        return names
    }

    private static func parameterNamesFromFragment(_ fragment: String) -> [String] {
        let query = fragment.contains("?")
            ? fragment.split(separator: "?", maxSplits: 1).last.map(String.init) ?? ""
            : fragment
        return URLComponents(string: "x://x?\(query)")?.queryItems?.map(\.name) ?? []
    }

    private static func bodyFields(_ body: KotlinByteArray?) -> String {
        guard let body else { return "" }
        let text = String(data: Data(kotlinByteArray: body), encoding: .utf8) ?? ""
        if text.hasPrefix("{") {
            return text.keysFromJsonObject().joined(separator: ",")
        }
        return text
            .split(separator: "&")
            .compactMap { pair in pair.split(separator: "=", maxSplits: 1).first.map(String.init) }
            .map { $0.removingPercentEncoding ?? $0 }
            .joined(separator: ",")
    }

    private static func classify(_ bodyText: String) -> String {
        if let alert = bodyText.casAlertTitle {
            return "alert=\(alert)"
        }
        if bodyText.localizedCaseInsensitiveContains("secState")
            || bodyText.localizedCaseInsensitiveContains("Safety Verify") {
            return "safety-html"
        }
        if bodyText.localizedCaseInsensitiveContains("<html") {
            if bodyText.localizedCaseInsensitiveContains("cas/login") {
                return "login-html"
            }
            if let title = bodyText.htmlTitle {
                return "html-title=\(title) html-text=\(bodyText.htmlSnippet)"
            }
            return "html-text=\(bodyText.htmlSnippet)"
        }
        if bodyText.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
            return bodyText.casJsonSummary
        }
        return "text-len=\(bodyText.count)"
    }
}

private extension String {
    var casAlertTitle: String? {
        let pattern = #"<el-alert\b[^>]*\btitle=["']([^"']+)["'][^>]*>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return nil
        }
        let range = NSRange(startIndex..<endIndex, in: self)
        guard let match = regex.firstMatch(in: self, range: range), match.numberOfRanges > 1 else {
            return nil
        }
        return Range(match.range(at: 1), in: self).map { String(self[$0]) }
    }

    var casJsonSummary: String {
        let code = firstJsonScalar(named: "code") ?? "?"
        let status = firstJsonScalar(named: "status")
        let need = firstJsonScalar(named: "need")
        return [
            "json(code=\(code)",
            status.map { "status=\($0)" },
            need.map { "need=\($0)" },
        ]
        .compactMap { $0 }
        .joined(separator: ",") + ")"
    }

    var htmlTitle: String? {
        let pattern = #"<title[^>]*>(.*?)</title>"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) else {
            return nil
        }
        let range = NSRange(startIndex..<endIndex, in: self)
        guard let match = regex.firstMatch(in: self, range: range), match.numberOfRanges > 1 else {
            return nil
        }
        return Range(match.range(at: 1), in: self).map { String(self[$0]).normalizedHtmlSnippet }
    }

    var htmlSnippet: String {
        replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
            .normalizedHtmlSnippet
    }

    private var normalizedHtmlSnippet: String {
        replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\t", with: " ")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(120)
            .description
    }

    func keysFromJsonObject() -> [String] {
        guard let regex = try? NSRegularExpression(pattern: #""([^"\\]+)"\s*:"#) else {
            return []
        }
        let range = NSRange(startIndex..<endIndex, in: self)
        return regex.matches(in: self, range: range).compactMap { match in
            Range(match.range(at: 1), in: self).map { String(self[$0]) }
        }
    }

    private func firstJsonScalar(named name: String) -> String? {
        let pattern = #""\#(NSRegularExpression.escapedPattern(for: name))"\s*:\s*("[^"]*"|true|false|-?\d+)"#
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }
        let range = NSRange(startIndex..<endIndex, in: self)
        guard let match = regex.firstMatch(in: self, range: range), match.numberOfRanges > 1 else {
            return nil
        }
        guard let valueRange = Range(match.range(at: 1), in: self) else { return nil }
        return String(self[valueRange]).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
    }
}

private extension KmpURLSessionHttpClient {
    static func defaultSession(cookieStorage: HTTPCookieStorage? = nil) -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        // URLSession follows CAS redirects internally; keep intermediate Set-Cookie
        // values in the same store as KMP, but let shared CookieAwareHttpClient be
        // the only layer that injects Cookie request headers.
        configuration.httpCookieAcceptPolicy = cookieStorage == nil ? .never : .always
        configuration.httpCookieStorage = cookieStorage
        configuration.httpShouldSetCookies = cookieStorage != nil
        return URLSession(
            configuration: configuration,
            delegate: CasLoginRedirectDelegate(),
            delegateQueue: nil
        )
    }
}

private extension String {
    func equalsIgnoringCase(_ other: String) -> Bool {
        compare(other, options: [.caseInsensitive]) == .orderedSame
    }
}

private extension URLRequest {
    var actualCookieNames: [String] {
        guard let cookieHeader = allHTTPHeaderFields?.first(where: { $0.key.equalsIgnoringCase("Cookie") })?.value else {
            return []
        }
        return cookieHeader
            .split(separator: ";")
            .compactMap { segment in
                segment
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(separator: "=", maxSplits: 1)
                    .first
                    .map(String.init)
            }
    }

    var isCasLoginPost: Bool {
        httpMethod?.equalsIgnoringCase("POST") == true &&
            url?.host?.equalsIgnoringCase("login.xjtu.edu.cn") == true &&
            url?.path.equalsIgnoringCase("/cas/login") == true
    }

    var isCasCallbackAuthorizeGet: Bool {
        httpMethod?.equalsIgnoringCase("GET") == true &&
            url?.isCasCallbackAuthorize == true
    }

    var isCasOauthAuthorizeGet: Bool {
        httpMethod?.equalsIgnoringCase("GET") == true &&
            url?.isCasOauthAuthorize == true
    }

    var isMobileJwappAuthorizeGet: Bool {
        guard httpMethod?.equalsIgnoringCase("GET") == true,
              let url,
              url.host?.equalsIgnoringCase("org.xjtu.edu.cn") == true,
              url.path.equalsIgnoringCase("/openplatform/oauth/authorize"),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else {
            return false
        }
        return components.queryItems?.contains { item in
            item.name.equalsIgnoringCase("redirectUri") &&
                item.value?.equalsIgnoringCase("http://jwapp.xjtu.edu.cn/app/index") == true
        } == true
    }

    var isInsecureMobileJwappCallback: Bool {
        url?.scheme?.equalsIgnoringCase("http") == true &&
            url?.host?.equalsIgnoringCase("jwapp.xjtu.edu.cn") == true &&
            url?.path.equalsIgnoringCase("/app/index") == true
    }

    var isSecureMobileJwappCallback: Bool {
        url?.scheme?.equalsIgnoringCase("https") == true &&
            url?.host?.equalsIgnoringCase("jwapp.xjtu.edu.cn") == true &&
            url?.path.equalsIgnoringCase("/app/index") == true
    }
}

private extension URL {
    var isCasCallbackAuthorize: Bool {
        host?.equalsIgnoringCase("login.xjtu.edu.cn") == true &&
            path.equalsIgnoringCase("/cas/oauth2.0/callbackAuthorize")
    }

    var isCasOauthAuthorize: Bool {
        host?.equalsIgnoringCase("login.xjtu.edu.cn") == true &&
            path.equalsIgnoringCase("/cas/oauth2.0/authorize")
    }
}

private extension Data {
    init(kotlinByteArray: KotlinByteArray) {
        var bytes = [UInt8]()
        bytes.reserveCapacity(Int(kotlinByteArray.size))
        for index in 0..<kotlinByteArray.size {
            bytes.append(UInt8(bitPattern: kotlinByteArray.get(index: index)))
        }
        self.init(bytes)
    }
}
#endif
