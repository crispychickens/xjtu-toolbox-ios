package com.xjtu.toolbox.shared.auth

import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import kotlinx.coroutines.flow.StateFlow

enum class AccessMode {
    AUTO,
    NORMAL,
    WEBVPN,
}

enum class SiteKey(val stableName: String) {
    JWXT("jwxt"),
    JWAPP("jwapp"),
    YWTB("ywtb"),
    CAMPUS_CARD("campus_card"),
    NOTICE("notice"),
    EMPTY_ROOM("empty_room"),
    SCHEDULE("schedule"),
    GRADE("grade"),
    LIBRARY("library"),
    COUPON("coupon"),
}

sealed interface AuthState {
    data object Anonymous : AuthState
    data class Authenticating(val username: String) : AuthState
    data class AwaitingCaptcha(val challenge: CaptchaChallenge) : AuthState
    data class AwaitingMfa(val challenge: MfaChallenge) : AuthState
    data class AwaitingAccountChoice(val challenge: AccountChoiceChallenge) : AuthState
    data class AwaitingBrowserAuth(val challenge: BrowserAuthChallenge) : AuthState
    data class Authenticated(val username: String, val activeSites: Set<SiteKey>) : AuthState
    data class SiteVerificationRequired(val username: String, val site: SiteKey, val message: String) : AuthState
    data class PasswordInvalidated(val siteName: String) : AuthState
}

data class CaptchaChallenge(
    val imageBase64: String,
    val site: SiteKey?,
)

data class MfaChallenge(
    val flow: MfaFlow,
    val maskedPhone: String,
    val site: SiteKey?,
)

enum class MfaFlow {
    MFA_DETECT,
    SAFETY_VERIFY,
}

enum class AccountType {
    UNDERGRADUATE,
    POSTGRADUATE,
    UNKNOWN,
}

data class AccountChoice(
    val id: String,
    val displayName: String,
    val accountType: AccountType,
)

data class AccountChoiceChallenge(
    val choices: List<AccountChoice>,
    val site: SiteKey?,
)

data class BrowserAuthChallenge(
    val loginUrl: String,
    val callbackScheme: String,
    val state: String,
    val site: SiteKey?,
)

data class BrowserAuthCallback(
    val rawUrl: String,
    val state: String,
    val ticket: String?,
    val code: String?,
    val site: SiteKey?,
)

sealed interface LoginOutcome {
    data class Success(val username: String) : LoginOutcome
    data class NeedCaptcha(val challenge: CaptchaChallenge) : LoginOutcome
    data class NeedMfa(val challenge: MfaChallenge) : LoginOutcome
    data class NeedAccountChoice(val challenge: AccountChoiceChallenge) : LoginOutcome
    data class NeedBrowserAuth(val challenge: BrowserAuthChallenge) : LoginOutcome
    data class Failure(val reason: AuthFailure) : LoginOutcome
}

sealed interface AuthFailure {
    data class InvalidPassword(val siteName: String) : AuthFailure
    data class RateLimited(val retryAfterMillis: Long, val reason: String) : AuthFailure
    data class BrowserAuthRejected(val message: String) : AuthFailure
    data class VerificationRejected(val message: String) : AuthFailure
    data class Network(val message: String) : AuthFailure
    data class ServiceChanged(val message: String) : AuthFailure
    data class Unknown(val message: String) : AuthFailure
}

interface SiteSession {
    val site: SiteKey
    val accessMode: AccessMode
    val isAuthenticated: Boolean

    suspend fun ensureAuthenticated(context: AuthContext): SiteSession
    suspend fun invalidate()
}

interface HttpSiteSession : SiteSession {
    val username: String?
    suspend fun execute(request: HttpRequest): HttpResponse
}

interface HeaderAuthenticatedSiteSession : HttpSiteSession {
    suspend fun adoptAuthenticatedHeaders(
        context: AuthContext,
        headers: Map<String, String>,
    ): SiteSession
}

data class Credentials(
    val username: String,
    val password: String,
)

data class AuthContext(
    val username: String,
    val credentials: Credentials? = null,
)

data class SiteVerificationContext(
    val finalUrl: String,
    val bodyText: String,
)

class SiteVerificationRequiredException(
    val site: SiteKey,
    override val message: String,
    val verificationContext: SiteVerificationContext? = null,
) : IllegalStateException(message)

interface AuthManager {
    val currentAccessMode: StateFlow<AccessMode>
    val authState: StateFlow<AuthState>

    suspend fun setAccessMode(mode: AccessMode)
    suspend fun restoreSavedCredentials(): AuthState
    suspend fun login(username: String, password: String): LoginOutcome
    suspend fun beginBrowserAuth(site: SiteKey? = null): LoginOutcome
    suspend fun beginSiteVerification(site: SiteKey): LoginOutcome
    suspend fun ensureSession(site: SiteKey): SiteSession
    suspend fun submitCaptcha(code: String): LoginOutcome
    suspend fun submitMfa(code: String): LoginOutcome
    suspend fun submitAccountChoice(choiceId: String): LoginOutcome
    suspend fun resumeBrowserAuth(callbackUrl: String): LoginOutcome
    suspend fun logout()
}
