package com.xjtu.toolbox.shared.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CredentialVault {
    suspend fun save(credentials: Credentials)
    suspend fun load(): Credentials?
    suspend fun clear()
}

interface AuthEngine {
    suspend fun login(credentials: Credentials, accessMode: AccessMode): EngineLoginResult
    suspend fun beginBrowserAuth(site: SiteKey?): EngineLoginResult
    suspend fun beginSiteVerification(
        credentials: Credentials,
        accessMode: AccessMode,
        site: SiteKey,
        context: SiteVerificationContext,
    ): EngineLoginResult
    suspend fun submitCaptcha(code: String): EngineLoginResult
    suspend fun submitMfa(code: String): EngineLoginResult
    suspend fun submitAccountChoice(choiceId: String): EngineLoginResult
    suspend fun resumeBrowserAuth(callback: BrowserAuthCallback): EngineLoginResult
    suspend fun logout()
}

sealed interface EngineLoginResult {
    data class Success(val username: String) : EngineLoginResult
    data class SiteSessionSuccess(
        val username: String,
        val site: SiteKey,
        val headers: Map<String, String>,
    ) : EngineLoginResult
    data class NeedCaptcha(val challenge: CaptchaChallenge) : EngineLoginResult
    data class NeedMfa(val challenge: MfaChallenge) : EngineLoginResult
    data class NeedAccountChoice(val challenge: AccountChoiceChallenge) : EngineLoginResult
    data class NeedBrowserAuth(val challenge: BrowserAuthChallenge) : EngineLoginResult
    data class VerificationRejected(val message: String) : EngineLoginResult
    data class InvalidPassword(val siteName: String) : EngineLoginResult
    data class NetworkError(val message: String) : EngineLoginResult
    data class ServiceChanged(val message: String) : EngineLoginResult
    data class UnknownError(val message: String) : EngineLoginResult
}

class SessionRegistry(
    private val factories: Map<SiteKey, (AccessMode) -> SiteSession>,
) {
    private val sessions = mutableMapOf<Pair<SiteKey, AccessMode>, SiteSession>()

    fun activeSites(): Set<SiteKey> =
        sessions.values.filter { it.isAuthenticated }.map { it.site }.toSet()

    fun session(site: SiteKey, mode: AccessMode): SiteSession {
        val key = site to mode
        return sessions.getOrPut(key) {
            factories[site]?.invoke(mode) ?: InMemorySiteSession(site, mode)
        }
    }

    suspend fun adoptAuthenticatedSession(
        site: SiteKey,
        mode: AccessMode,
        context: AuthContext,
        headers: Map<String, String>,
    ): SiteSession {
        val session = session(site, mode)
        return if (session is HeaderAuthenticatedSiteSession) {
            session.adoptAuthenticatedHeaders(context, headers)
        } else {
            session.ensureAuthenticated(context)
        }
    }

    suspend fun invalidateAll() {
        sessions.values.forEach { it.invalidate() }
        sessions.clear()
    }
}

class DefaultAuthManager(
    private val engine: AuthEngine,
    private val vault: CredentialVault,
    private val registry: SessionRegistry,
    initialAccessMode: AccessMode = AccessMode.AUTO,
    private val accessModeStore: AccessModeStore = InMemoryAccessModeStore(initialAccessMode),
    private val attemptGovernor: LoginAttemptGovernor = LoginAttemptGovernor(),
    private val challengeAttemptGovernor: LoginAttemptGovernor = LoginAttemptGovernor(),
    private val browserAuthCallbackParser: BrowserAuthCallbackParser = BrowserAuthCallbackParser(),
) : AuthManager {
    private val lock = Mutex()
    private var pendingCredentials: Credentials? = null
    private var authContext: AuthContext? = null
    private var lastInvalidCredentials: Credentials? = null
    private var pendingSiteVerification: PendingSiteVerification? = null

    private val _currentAccessMode = MutableStateFlow(accessModeStore.load())
    override val currentAccessMode: StateFlow<AccessMode> = _currentAccessMode

    private val _authState = MutableStateFlow<AuthState>(AuthState.Anonymous)
    override val authState: StateFlow<AuthState> = _authState

    override suspend fun setAccessMode(mode: AccessMode) = lock.withLock {
        if (_currentAccessMode.value == mode) return@withLock
        _currentAccessMode.value = mode
        accessModeStore.save(mode)
        registry.invalidateAll()
        val context = authContext
        if (context != null && _authState.value is AuthState.Authenticated) {
            _authState.value = AuthState.Authenticated(context.username, emptySet())
        }
    }

    override suspend fun restoreSavedCredentials(): AuthState = lock.withLock {
        when (val current = _authState.value) {
            is AuthState.Authenticated,
            is AuthState.Authenticating,
            is AuthState.AwaitingCaptcha,
            is AuthState.AwaitingMfa,
            is AuthState.AwaitingAccountChoice,
            is AuthState.AwaitingBrowserAuth,
            is AuthState.SiteVerificationRequired,
            -> return@withLock current
            else -> Unit
        }
        val credentials = vault.load() ?: return@withLock _authState.value
        authContext = AuthContext(username = credentials.username, credentials = credentials)
        AuthState.Authenticated(credentials.username, registry.activeSites()).also {
            _authState.value = it
        }
    }

    override suspend fun login(username: String, password: String): LoginOutcome = lock.withLock {
        val nextCredentials = Credentials(username = username.trim(), password = password)
        if (nextCredentials.username.isBlank() || nextCredentials.password.isBlank()) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("用户名和密码不能为空"))
        }
        if (lastInvalidCredentials == nextCredentials) {
            return@withLock LoginOutcome.Failure(AuthFailure.InvalidPassword("上次失败的凭据已被阻止，请修改密码后再试"))
        }
        when (val pending = _authState.value) {
            is AuthState.AwaitingCaptcha -> return@withLock LoginOutcome.NeedCaptcha(pending.challenge)
            is AuthState.AwaitingMfa -> return@withLock LoginOutcome.NeedMfa(pending.challenge)
            is AuthState.AwaitingAccountChoice -> return@withLock LoginOutcome.NeedAccountChoice(pending.challenge)
            is AuthState.AwaitingBrowserAuth -> return@withLock LoginOutcome.NeedBrowserAuth(pending.challenge)
            else -> Unit
        }
        pendingSiteVerification = null
        when (val decision = attemptGovernor.beforePasswordLogin(nextCredentials.username)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        _authState.value = AuthState.Authenticating(nextCredentials.username)
        pendingCredentials = nextCredentials
        applyEngineResult(
            result = engine.login(nextCredentials, _currentAccessMode.value),
            attemptedCredentials = nextCredentials,
            transientFailureUsername = nextCredentials.username,
            challengeKey = null,
        )
    }

    override suspend fun beginBrowserAuth(site: SiteKey?): LoginOutcome = lock.withLock {
        when (val pending = _authState.value) {
            is AuthState.AwaitingCaptcha -> return@withLock LoginOutcome.NeedCaptcha(pending.challenge)
            is AuthState.AwaitingMfa -> return@withLock LoginOutcome.NeedMfa(pending.challenge)
            is AuthState.AwaitingAccountChoice -> return@withLock LoginOutcome.NeedAccountChoice(pending.challenge)
            is AuthState.AwaitingBrowserAuth -> return@withLock LoginOutcome.NeedBrowserAuth(pending.challenge)
            else -> Unit
        }
        when (val decision = beforeBrowserAttempt(site)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        val result = engine.beginBrowserAuth(site)
        when (result) {
            is EngineLoginResult.NetworkError,
            is EngineLoginResult.ServiceChanged,
            is EngineLoginResult.UnknownError,
            -> recordBrowserFailure(site)
            else -> recordBrowserSuccess(site)
        }
        applyEngineResult(
            result = result,
            attemptedCredentials = null,
            transientFailureUsername = null,
            challengeKey = null,
        )
    }

    override suspend fun beginSiteVerification(site: SiteKey): LoginOutcome = lock.withLock {
        val current = _authState.value
        if (current !is AuthState.SiteVerificationRequired || current.site != site) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("没有待处理的站点验证"))
        }
        val pending = pendingSiteVerification
            ?: return@withLock LoginOutcome.Failure(AuthFailure.ServiceChanged("站点未提供可继续的验证上下文，请稍后重试"))
        if (pending.site != site) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("站点验证上下文不匹配"))
        }
        val credentials = authContext?.credentials ?: vault.load()
            ?: return@withLock LoginOutcome.Failure(AuthFailure.Unknown("缺少可用于站点验证的已保存凭据"))
        val challengeKey = pending.attemptKey(credentials.username)
        when (val decision = challengeAttemptGovernor.beforeChallengeResponse(challengeKey)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        pendingCredentials = credentials
        applyEngineResult(
            result = engine.beginSiteVerification(
                credentials = credentials,
                accessMode = _currentAccessMode.value,
                site = site,
                context = pending.context,
            ),
            attemptedCredentials = credentials,
            transientFailureUsername = credentials.username,
            challengeKey = challengeKey,
            preserveSiteVerification = pending,
            preserveSiteVerificationUsername = credentials.username,
        )
    }

    override suspend fun ensureSession(site: SiteKey): SiteSession = lock.withLock {
        val context = authContext ?: vault.load()?.let { AuthContext(username = it.username, credentials = it) }
            ?: error("No saved credentials or active browser-authenticated context")
        authContext = context
        val session = registry.session(site, _currentAccessMode.value)
        val authenticatedSession = if (session.isAuthenticated) {
            session
        } else {
            try {
                session.ensureAuthenticated(context)
            } catch (failure: SiteVerificationRequiredException) {
                session.invalidate()
                pendingSiteVerification = failure.verificationContext?.let {
                    PendingSiteVerification(site = failure.site, context = it)
                }
                _authState.value = AuthState.SiteVerificationRequired(
                    username = context.username,
                    site = failure.site,
                    message = failure.message,
                )
                throw failure
            }
        }
        _authState.value = AuthState.Authenticated(context.username, registry.activeSites())
        authenticatedSession
    }

    override suspend fun submitCaptcha(code: String): LoginOutcome = lock.withLock {
        val pending = _authState.value
        if (pending !is AuthState.AwaitingCaptcha) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("没有正在进行的图形验证码"))
        }
        val challengeKey = pending.challenge.attemptKey()
        when (val decision = challengeAttemptGovernor.beforeChallengeResponse(challengeKey)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        applyEngineResult(
            result = engine.submitCaptcha(code.trim()),
            attemptedCredentials = pendingCredentials,
            transientFailureUsername = pendingCredentials?.username,
            challengeKey = challengeKey,
        )
    }

    override suspend fun submitMfa(code: String): LoginOutcome = lock.withLock {
        val pending = _authState.value
        if (pending !is AuthState.AwaitingMfa) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("没有正在进行的 MFA 验证"))
        }
        val challengeKey = pending.challenge.attemptKey()
        when (val decision = challengeAttemptGovernor.beforeChallengeResponse(challengeKey)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        applyEngineResult(
            result = engine.submitMfa(code.trim()),
            attemptedCredentials = pendingCredentials,
            transientFailureUsername = pendingCredentials?.username,
            challengeKey = challengeKey,
        )
    }

    override suspend fun submitAccountChoice(choiceId: String): LoginOutcome = lock.withLock {
        val pending = _authState.value
        if (pending !is AuthState.AwaitingAccountChoice) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("没有正在进行的账号类型选择"))
        }
        val trimmedChoiceId = choiceId.trim()
        if (trimmedChoiceId.isBlank()) {
            return@withLock LoginOutcome.Failure(AuthFailure.VerificationRejected("请选择账号类型"))
        }
        val challengeKey = pending.challenge.attemptKey()
        when (val decision = challengeAttemptGovernor.beforeChallengeResponse(challengeKey)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        applyEngineResult(
            result = engine.submitAccountChoice(trimmedChoiceId),
            attemptedCredentials = pendingCredentials,
            transientFailureUsername = pendingCredentials?.username,
            challengeKey = challengeKey,
        )
    }

    override suspend fun resumeBrowserAuth(callbackUrl: String): LoginOutcome = lock.withLock {
        val pending = _authState.value
        if (pending !is AuthState.AwaitingBrowserAuth) {
            return@withLock LoginOutcome.Failure(AuthFailure.Unknown("没有正在进行的官方网页登录"))
        }
        when (val decision = beforeBrowserAttempt(pending.challenge.site)) {
            LoginAttemptDecision.Allowed -> Unit
            is LoginAttemptDecision.Rejected -> {
                return@withLock LoginOutcome.Failure(
                    AuthFailure.RateLimited(
                        retryAfterMillis = decision.retryAfterMillis,
                        reason = decision.reason,
                    ),
                )
            }
        }
        val callback = when (val parsed = browserAuthCallbackParser.parse(pending.challenge, callbackUrl)) {
            is BrowserAuthCallbackParseResult.Valid -> parsed.callback
            is BrowserAuthCallbackParseResult.Invalid -> {
                recordBrowserFailure(pending.challenge.site)
                return@withLock LoginOutcome.Failure(AuthFailure.BrowserAuthRejected(parsed.reason))
            }
        }
        val result = engine.resumeBrowserAuth(callback)
        when (result) {
            is EngineLoginResult.Success,
            is EngineLoginResult.NeedMfa,
            is EngineLoginResult.NeedAccountChoice,
            is EngineLoginResult.NeedBrowserAuth,
            -> recordBrowserSuccess(pending.challenge.site)
            else -> recordBrowserFailure(pending.challenge.site)
        }
        applyEngineResult(
            result = result,
            attemptedCredentials = pendingCredentials,
            transientFailureUsername = pendingCredentials?.username,
            challengeKey = null,
        )
    }

    override suspend fun logout() = lock.withLock {
        pendingCredentials = null
        authContext = null
        lastInvalidCredentials = null
        pendingSiteVerification = null
        attemptGovernor.clear()
        challengeAttemptGovernor.clear()
        vault.clear()
        registry.invalidateAll()
        engine.logout()
        _authState.value = AuthState.Anonymous
    }

    private suspend fun applyEngineResult(
        result: EngineLoginResult,
        attemptedCredentials: Credentials?,
        transientFailureUsername: String?,
        challengeKey: String?,
        preserveSiteVerification: PendingSiteVerification? = null,
        preserveSiteVerificationUsername: String? = null,
    ): LoginOutcome = when (result) {
        is EngineLoginResult.Success -> applySuccess(
            username = result.username,
            site = null,
            headers = emptyMap(),
            attemptedCredentials = attemptedCredentials,
            challengeKey = challengeKey,
        )
        is EngineLoginResult.SiteSessionSuccess -> applySuccess(
            username = result.username,
            site = result.site,
            headers = result.headers,
            attemptedCredentials = attemptedCredentials,
            challengeKey = challengeKey,
        )
        is EngineLoginResult.NeedCaptcha -> {
            _authState.value = AuthState.AwaitingCaptcha(result.challenge)
            LoginOutcome.NeedCaptcha(result.challenge)
        }
        is EngineLoginResult.NeedMfa -> {
            _authState.value = AuthState.AwaitingMfa(result.challenge)
            LoginOutcome.NeedMfa(result.challenge)
        }
        is EngineLoginResult.NeedAccountChoice -> {
            _authState.value = AuthState.AwaitingAccountChoice(result.challenge)
            LoginOutcome.NeedAccountChoice(result.challenge)
        }
        is EngineLoginResult.NeedBrowserAuth -> {
            _authState.value = AuthState.AwaitingBrowserAuth(result.challenge)
            LoginOutcome.NeedBrowserAuth(result.challenge)
        }
        is EngineLoginResult.VerificationRejected -> {
            if (challengeKey != null) challengeAttemptGovernor.recordChallengeFailure(challengeKey)
            LoginOutcome.Failure(AuthFailure.VerificationRejected(result.message))
        }
        is EngineLoginResult.InvalidPassword -> {
            if (challengeKey != null) challengeAttemptGovernor.recordChallengeFailure(challengeKey)
            if (attemptedCredentials != null) lastInvalidCredentials = attemptedCredentials
            invalidPassword(result.siteName)
        }
        is EngineLoginResult.NetworkError -> transientFailure(
            username = transientFailureUsername,
            challengeKey = challengeKey,
            failure = AuthFailure.Network(result.message),
            preserveSiteVerification = preserveSiteVerification,
            preserveSiteVerificationUsername = preserveSiteVerificationUsername,
        )
        is EngineLoginResult.ServiceChanged -> transientFailure(
            username = transientFailureUsername,
            challengeKey = challengeKey,
            failure = AuthFailure.ServiceChanged(result.message),
            preserveSiteVerification = preserveSiteVerification,
            preserveSiteVerificationUsername = preserveSiteVerificationUsername,
        )
        is EngineLoginResult.UnknownError -> transientFailure(
            username = transientFailureUsername,
            challengeKey = challengeKey,
            failure = AuthFailure.Unknown(result.message),
            preserveSiteVerification = preserveSiteVerification,
            preserveSiteVerificationUsername = preserveSiteVerificationUsername,
        )
    }

    private suspend fun applySuccess(
        username: String,
        site: SiteKey?,
        headers: Map<String, String>,
        attemptedCredentials: Credentials?,
        challengeKey: String?,
    ): LoginOutcome.Success {
        val credentials = attemptedCredentials ?: pendingCredentials
        val context = AuthContext(username = username, credentials = credentials)
        authContext = context
        if (credentials != null) vault.save(credentials)
        if (site != null) {
            registry.adoptAuthenticatedSession(
                site = site,
                mode = _currentAccessMode.value,
                context = context,
                headers = headers,
            )
        }
        attemptGovernor.recordPasswordSuccess(credentials?.username ?: username)
        if (challengeKey != null) challengeAttemptGovernor.recordChallengeSuccess(challengeKey)
        lastInvalidCredentials = null
        pendingSiteVerification = null
        _authState.value = AuthState.Authenticated(username, registry.activeSites())
        return LoginOutcome.Success(username)
    }

    private suspend fun invalidPassword(siteName: String): LoginOutcome.Failure {
        pendingCredentials = null
        authContext = null
        pendingSiteVerification = null
        vault.clear()
        registry.invalidateAll()
        _authState.value = AuthState.PasswordInvalidated(siteName)
        return LoginOutcome.Failure(AuthFailure.InvalidPassword(siteName))
    }

    private fun transientFailure(
        username: String?,
        challengeKey: String?,
        failure: AuthFailure,
        preserveSiteVerification: PendingSiteVerification? = null,
        preserveSiteVerificationUsername: String? = null,
    ): LoginOutcome.Failure {
        if (username != null) attemptGovernor.recordPasswordTransientFailure(username)
        if (challengeKey != null) challengeAttemptGovernor.recordChallengeFailure(challengeKey)
        if (preserveSiteVerification != null && preserveSiteVerificationUsername != null) {
            pendingSiteVerification = preserveSiteVerification
            _authState.value = AuthState.SiteVerificationRequired(
                username = preserveSiteVerificationUsername,
                site = preserveSiteVerification.site,
                message = failure.messageText(),
            )
            return LoginOutcome.Failure(failure)
        }
        pendingSiteVerification = null
        _authState.value = AuthState.Anonymous
        return LoginOutcome.Failure(failure)
    }

    private fun AuthFailure.messageText(): String = when (this) {
        is AuthFailure.BrowserAuthRejected -> message
        is AuthFailure.InvalidPassword -> siteName
        is AuthFailure.Network -> message
        is AuthFailure.RateLimited -> reason
        is AuthFailure.ServiceChanged -> message
        is AuthFailure.Unknown -> message
        is AuthFailure.VerificationRejected -> message
    }

    private fun CaptchaChallenge.attemptKey(): String =
        "captcha:${site?.stableName ?: "global"}:${pendingCredentials?.username ?: "anonymous"}"

    private fun MfaChallenge.attemptKey(): String =
        "mfa:${site?.stableName ?: "global"}:$flow:$maskedPhone"

    private fun AccountChoiceChallenge.attemptKey(): String =
        "account-choice:${site?.stableName ?: "global"}:${pendingCredentials?.username ?: "anonymous"}"

    private fun beforeBrowserAttempt(site: SiteKey?): LoginAttemptDecision =
        challengeAttemptGovernor.beforeAttempt(
            scope = browserAttemptKey(site),
            rejectedReason = "网页登录请求过于频繁，请稍后再试",
        )

    private fun recordBrowserSuccess(site: SiteKey?) {
        challengeAttemptGovernor.recordSuccess(browserAttemptKey(site))
    }

    private fun recordBrowserFailure(site: SiteKey?) {
        challengeAttemptGovernor.recordTransientFailure(browserAttemptKey(site))
    }

    private fun browserAttemptKey(site: SiteKey?): String =
        "browser:${site?.stableName ?: "global"}"

    private data class PendingSiteVerification(
        val site: SiteKey,
        val context: SiteVerificationContext,
    ) {
        fun attemptKey(username: String): String =
            "site-verification:${site.stableName}:$username"
    }
}

class InMemorySiteSession(
    override val site: SiteKey,
    override val accessMode: AccessMode,
) : SiteSession {
    override var isAuthenticated: Boolean = false
        private set

    override suspend fun ensureAuthenticated(context: AuthContext): SiteSession {
        require(context.username.isNotBlank()) { "username is required" }
        isAuthenticated = true
        return this
    }

    override suspend fun invalidate() {
        isAuthenticated = false
    }
}

class InMemoryCredentialVault : CredentialVault {
    private var stored: Credentials? = null

    override suspend fun save(credentials: Credentials) {
        stored = credentials
    }

    override suspend fun load(): Credentials? = stored

    override suspend fun clear() {
        stored = null
    }
}
