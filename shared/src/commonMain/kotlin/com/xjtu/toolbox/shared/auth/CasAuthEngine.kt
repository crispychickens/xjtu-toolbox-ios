 package com.xjtu.toolbox.shared.auth

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse

data class CasAuthConfig(
    val loginUrl: String = "https://login.xjtu.edu.cn/cas/login",
    val captchaUrl: String = "https://login.xjtu.edu.cn/cas/captcha.jpg",
    val publicKeyUrl: String = "https://login.xjtu.edu.cn/cas/jwt/publicKey",
    val mfaDetectUrl: String = "https://login.xjtu.edu.cn/cas/mfa/detect",
    val casBaseUrl: String = "https://login.xjtu.edu.cn/cas",
    val attestSendUrl: String = "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
    val attestValidUrl: String = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
    val siteName: String = "CAS",
)

fun interface PasswordEncryptor {
    suspend fun encrypt(password: String, publicKey: String): PasswordEncryptionResult
}

sealed interface PasswordEncryptionResult {
    data class Success(val encryptedPassword: String) : PasswordEncryptionResult
    data class Failure(val message: String) : PasswordEncryptionResult
}

fun interface VisitorIdProvider {
    fun visitorId(): String
}

class StaticVisitorIdProvider(private val value: String) : VisitorIdProvider {
    override fun visitorId(): String = value
}

class CasAuthEngine(
    private val httpClient: HttpClient,
    private val passwordEncryptor: PasswordEncryptor,
    private val visitorIdProvider: VisitorIdProvider,
    private val config: CasAuthConfig = CasAuthConfig(),
    private val webVpnHttpClient: HttpClient? = null,
    private val browserAuthHandler: BrowserAuthHandler? = null,
) : AuthEngine {
    private var pendingCredentials: Credentials? = null
    private var pendingEncryptedPassword: String? = null
    private var pendingLoginForm: PendingLoginForm? = null
    private var pendingCaptchaCode: String? = null
    private var pendingMfa: PendingMfa? = null
    private var pendingAccountChoice: PendingAccountChoice? = null
    private var activeHttpClient: HttpClient = httpClient

    override suspend fun login(credentials: Credentials, accessMode: AccessMode): EngineLoginResult {
        resetPending()
        pendingCredentials = credentials
        activeHttpClient = httpClientFor(accessMode)

        val initial = activeHttpClient.execute(HttpRequest(url = config.loginUrl))
        return continueCasLoginFromResponse(credentials, initial, site = null)
    }

    override suspend fun beginSiteVerification(
        credentials: Credentials,
        accessMode: AccessMode,
        site: SiteKey,
        context: SiteVerificationContext,
    ): EngineLoginResult {
        resetPending()
        pendingCredentials = credentials
        activeHttpClient = httpClientFor(accessMode)

        val initial = if (context.bodyText.isBlank() && context.finalUrl.isNotBlank()) {
            activeHttpClient.execute(HttpRequest(url = context.finalUrl))
        } else {
            HttpResponse(code = 200, finalUrl = context.finalUrl, bodyText = context.bodyText)
        }
        return continueCasLoginFromResponse(credentials, initial, site = site)
    }

    private suspend fun continueCasLoginFromResponse(
        credentials: Credentials,
        initial: HttpResponse,
        site: SiteKey?,
    ): EngineLoginResult {
        if (CasHtmlParser.isSafetyVerifyPage(initial.bodyText)) {
            return prepareSafetyVerifyChallenge(initial, site)
        }

        val form = CasHtmlParser.parseLoginForm(initial.bodyText)
        val execution = form.execution ?: return if (looksLikeAuthenticatedResponse(initial)) {
            EngineLoginResult.Success(credentials.username)
        } else {
            EngineLoginResult.ServiceChanged("CAS 登录页缺少 execution 字段")
        }
        val publicKey = form.rsaPublicKey ?: fetchPublicKey(initial.finalUrl)
            ?: return EngineLoginResult.ServiceChanged("CAS RSA 公钥不可用")
        val encryptedPassword = when (val encrypted = passwordEncryptor.encrypt(credentials.password, publicKey)) {
            is PasswordEncryptionResult.Success -> encrypted.encryptedPassword
            is PasswordEncryptionResult.Failure -> return EngineLoginResult.ServiceChanged(encrypted.message)
        }

        pendingEncryptedPassword = encryptedPassword
        pendingLoginForm = PendingLoginForm(
            postUrl = initial.finalUrl,
            execution = execution,
            hasCaptcha = form.hasCaptcha,
            failCount = form.failCount,
            mfaEnabled = extractMfaEnabled(initial.bodyText),
            site = site,
        )

        if (form.hasCaptcha) {
            return prepareCaptchaChallenge(site)
        }

        if (pendingLoginForm?.mfaEnabled == true) {
            when (val detect = detectMfa(credentials.username, encryptedPassword, initial.finalUrl)) {
                is MfaDetectResult.NeedChallenge -> return prepareMfaDetectChallenge(detect.state, site)
                is MfaDetectResult.NotNeeded -> return postCasLogin(mfaState = detect.state, trustAgent = false)
                is MfaDetectResult.Failed -> return EngineLoginResult.ServiceChanged(detect.message)
            }
        }

        return postCasLogin(mfaState = "", trustAgent = false)
    }

    override suspend fun beginBrowserAuth(site: SiteKey?): EngineLoginResult =
        browserAuthHandler?.begin(site) ?: unsupportedBrowserAuth()

    override suspend fun submitCaptcha(code: String): EngineLoginResult {
        if (pendingLoginForm?.hasCaptcha != true) {
            return EngineLoginResult.VerificationRejected("没有待处理的图形验证码")
        }
        if (code.isBlank()) return EngineLoginResult.VerificationRejected("图形验证码不能为空")
        pendingCaptchaCode = code

        val credentials = pendingCredentials ?: return EngineLoginResult.UnknownError("登录凭据丢失")
        val encryptedPassword = pendingEncryptedPassword ?: return EngineLoginResult.UnknownError("加密密码丢失")
        val form = pendingLoginForm ?: return EngineLoginResult.UnknownError("CAS 登录表单丢失")
        if (form.mfaEnabled) {
            when (val detect = detectMfa(credentials.username, encryptedPassword, form.postUrl)) {
                is MfaDetectResult.NeedChallenge -> return prepareMfaDetectChallenge(detect.state, form.site)
                is MfaDetectResult.NotNeeded -> return postCasLogin(mfaState = detect.state, trustAgent = false)
                is MfaDetectResult.Failed -> return EngineLoginResult.ServiceChanged(detect.message)
            }
        }

        return postCasLogin(mfaState = "", trustAgent = false)
    }

    override suspend fun submitMfa(code: String): EngineLoginResult {
        val challenge = pendingMfa ?: return EngineLoginResult.VerificationRejected("没有待处理的二次验证")
        if (code.isBlank()) return EngineLoginResult.VerificationRejected("验证码不能为空")

        val verification = verifySecurePhoneCode(challenge.gid, code)
        if (verification != null) return EngineLoginResult.VerificationRejected(verification)

        return when (challenge.flow) {
            MfaFlow.MFA_DETECT -> postCasLogin(mfaState = challenge.state, trustAgent = true)
            MfaFlow.SAFETY_VERIFY -> submitSafetyVerify(challenge)
        }
    }

    override suspend fun submitAccountChoice(choiceId: String): EngineLoginResult {
        val pending = pendingAccountChoice ?: return EngineLoginResult.VerificationRejected("没有待处理的账号类型选择")
        val choice = pending.choices.firstOrNull { it.id == choiceId }
            ?: return EngineLoginResult.VerificationRejected("账号类型无效")

        val response = activeHttpClient.execute(
            formPost(
                url = pending.postUrl,
                fields = listOf(
                    "execution" to pending.execution,
                    "_eventId" to "submit",
                    "geolocation" to "",
                    "fpVisitorId" to visitorIdProvider.visitorId(),
                    "trustAgent" to if (pendingMfa != null) "true" else "",
                    "username" to choice.id,
                    "useDefault" to "false",
                ),
            ),
        )
        return classifyLoginResponse(response, pending.site)
    }

    override suspend fun resumeBrowserAuth(callback: BrowserAuthCallback): EngineLoginResult =
        browserAuthHandler?.resume(callback) ?: unsupportedBrowserAuth()

    override suspend fun logout() {
        resetPending()
    }

    private suspend fun fetchPublicKey(referer: String): String? {
        val response = activeHttpClient.execute(
            HttpRequest(
                url = config.publicKeyUrl,
                headers = mapOf("Referer" to referer),
            ),
        )
        val key = response.bodyText.trim()
        if (!response.isSuccessful || key.isBlank() || key.contains("<html", ignoreCase = true)) return null
        return key
    }

    private suspend fun detectMfa(username: String, encryptedPassword: String, referer: String): MfaDetectResult {
        val response = activeHttpClient.execute(
            formPost(
                url = config.mfaDetectUrl,
                referer = referer,
                fields = listOf(
                    "username" to username,
                    "password" to encryptedPassword,
                    "fpVisitorId" to visitorIdProvider.visitorId(),
                ),
            ),
        )
        if (!response.isSuccessful) return MfaDetectResult.Failed("MFA detect 返回 HTTP ${response.code}")
        val state = JsonField.string(response.bodyText, "state")
        val need = JsonField.boolean(response.bodyText, "need")
            ?: return MfaDetectResult.Failed("MFA detect 返回缺少 need 字段")
        if (state.isNullOrBlank()) return MfaDetectResult.Failed("MFA detect 返回缺少 state 字段")
        return if (need) MfaDetectResult.NeedChallenge(state) else MfaDetectResult.NotNeeded(state)
    }

    private fun httpClientFor(accessMode: AccessMode): HttpClient =
        when (accessMode) {
            AccessMode.WEBVPN -> webVpnHttpClient ?: httpClient
            AccessMode.AUTO,
            AccessMode.NORMAL,
            -> httpClient
        }

    private suspend fun prepareCaptchaChallenge(site: SiteKey?): EngineLoginResult {
        val response = activeHttpClient.execute(HttpRequest(url = config.captchaUrl))
        if (!response.isSuccessful || response.bodyText.isBlank()) {
            return EngineLoginResult.ServiceChanged("CAS 图形验证码图片不可用")
        }
        return EngineLoginResult.NeedCaptcha(
            CaptchaChallenge(
                imageBase64 = response.bodyText.trim(),
                site = site,
            ),
        )
    }

    private suspend fun prepareMfaDetectChallenge(state: String, site: SiteKey?): EngineLoginResult =
        prepareSecurePhoneChallenge(
            flow = MfaFlow.MFA_DETECT,
            state = state,
            safety = null,
            site = site,
        )

    private suspend fun prepareSafetyVerifyChallenge(response: HttpResponse, site: SiteKey?): EngineLoginResult {
        val form = CasHtmlParser.parseSafetyVerifyForm(response.bodyText)
        val secState = form.secState ?: return EngineLoginResult.ServiceChanged("Safety Verify 页面缺少 secState")
        val execution = form.execution ?: return EngineLoginResult.ServiceChanged("Safety Verify 页面缺少 execution")
        return prepareSecurePhoneChallenge(
            flow = MfaFlow.SAFETY_VERIFY,
            state = secState,
            safety = PendingSafetyVerify(
                secState = secState,
                execution = execution,
                eventId = form.eventId,
                submitValue = form.submitValue,
                triggerUrl = response.finalUrl,
            ),
            site = site,
        )
    }

    private suspend fun prepareSecurePhoneChallenge(
        flow: MfaFlow,
        state: String,
        safety: PendingSafetyVerify?,
        site: SiteKey?,
    ): EngineLoginResult {
        val phone = fetchSecurePhone(flow, state)
            ?: return EngineLoginResult.ServiceChanged("二次验证手机号不可用")
        val sendError = sendSecurePhoneCode(phone.gid)
        if (sendError != null) return EngineLoginResult.ServiceChanged(sendError)

        pendingMfa = PendingMfa(
            flow = flow,
            state = state,
            gid = phone.gid,
            maskedPhone = phone.maskedPhone,
            safety = safety,
            site = site,
        )
        return EngineLoginResult.NeedMfa(
            MfaChallenge(
                flow = flow,
                maskedPhone = phone.maskedPhone,
                site = site,
            ),
        )
    }

    private suspend fun fetchSecurePhone(flow: MfaFlow, state: String): SecurePhone? {
        val response = activeHttpClient.execute(
            HttpRequest(
                url = "${config.casBaseUrl}/${flow.pathSegment()}/initByType/securephone?state=${urlEncode(state)}",
            ),
        )
        if (!response.isSuccessful || JsonField.int(response.bodyText, "code") != 0) return null
        val gid = JsonField.string(response.bodyText, "gid")
        val phone = JsonField.string(response.bodyText, "securePhone")
        if (gid.isNullOrBlank() || phone.isNullOrBlank()) return null
        return SecurePhone(gid = gid, maskedPhone = phone)
    }

    private suspend fun sendSecurePhoneCode(gid: String): String? {
        val response = activeHttpClient.execute(jsonPost(config.attestSendUrl, mapOf("gid" to gid)))
        return response.casJsonError()
    }

    private suspend fun verifySecurePhoneCode(gid: String, code: String): String? {
        val response = activeHttpClient.execute(jsonPost(config.attestValidUrl, mapOf("gid" to gid, "code" to code)))
        val error = response.casJsonError()
        if (error != null) return error
        val status = JsonField.string(response.bodyText, "status")
        return if (status == null || status == "2") null else "验证码验证失败"
    }

    private suspend fun postCasLogin(mfaState: String, trustAgent: Boolean): EngineLoginResult {
        val credentials = pendingCredentials ?: return EngineLoginResult.UnknownError("登录凭据丢失")
        val encryptedPassword = pendingEncryptedPassword ?: return EngineLoginResult.UnknownError("加密密码丢失")
        val form = pendingLoginForm ?: return EngineLoginResult.UnknownError("CAS 登录表单丢失")
        if (form.hasCaptcha && pendingCaptchaCode.isNullOrBlank()) {
            return prepareCaptchaChallenge(form.site)
        }

        val response = activeHttpClient.execute(
            formPost(
                url = form.postUrl,
                fields = buildList {
                    add("username" to credentials.username)
                    add("password" to encryptedPassword)
                    add("execution" to form.execution)
                    add("_eventId" to "submit")
                    add("submit1" to "Login1")
                    add("fpVisitorId" to visitorIdProvider.visitorId())
                    add("captcha" to (pendingCaptchaCode ?: ""))
                    add("currentMenu" to "1")
                    add("failN" to form.failCount.toString())
                    if (mfaState.isNotBlank()) {
                        add("mfaState" to mfaState)
                        add("trustAgent" to if (trustAgent) "true" else "")
                    }
                    add("geolocation" to "")
                },
            ),
        )
        return classifyLoginResponse(response, form.site)
    }

    private suspend fun submitSafetyVerify(challenge: PendingMfa): EngineLoginResult {
        val safety = challenge.safety ?: return EngineLoginResult.ServiceChanged("Safety Verify 上下文丢失")
        val response = activeHttpClient.execute(
            formPost(
                url = safety.triggerUrl,
                referer = safety.triggerUrl,
                fields = listOf(
                    "secState" to safety.secState,
                    "execution" to safety.execution,
                    "_eventId" to safety.eventId,
                    "geolocation" to "",
                    "fpVisitorId" to visitorIdProvider.visitorId(),
                    "submit" to safety.submitValue,
                ),
            ),
        )
        return classifyLoginResponse(response, challenge.site)
    }

    private suspend fun classifyLoginResponse(
        response: HttpResponse,
        site: SiteKey? = null,
        allowSiteLandingRetry: Boolean = false,
    ): EngineLoginResult {
        if (response.code == 401) return EngineLoginResult.InvalidPassword(config.siteName)
        val redirectLocation = response.redirectLocation()
        if (response.code in 300..399 && redirectLocation != null) {
            if (site != null) {
                if (!allowSiteLandingRetry) {
                    val landingResult = retrySiteLandingAfterCasRedirect(site)
                    if (site == SiteKey.GRADE) return landingResult
                    if (site == SiteKey.CAMPUS_CARD) return landingResult
                    if (landingResult !is EngineLoginResult.ServiceChanged) return landingResult
                }
                return classifyLoginResponse(
                    activeHttpClient.execute(HttpRequest(url = redirectLocation)),
                    site,
                    allowSiteLandingRetry = true,
                )
            }
            val username = pendingCredentials?.username ?: "cas"
            resetPending()
            return EngineLoginResult.Success(username)
        }

        extractAlertMessage(response.bodyText)?.let { message ->
            if (message.isVerificationFailureMessage()) {
                return EngineLoginResult.VerificationRejected(message)
            }
            return EngineLoginResult.InvalidPassword(message)
        }

        if (CasHtmlParser.isSafetyVerifyPage(response.bodyText)) {
            return prepareSafetyVerifyChallenge(response, site)
        }

        val form = CasHtmlParser.parseLoginForm(response.bodyText)
        val accountChoices = CasHtmlParser.parseAccountChoices(response.bodyText)
        if (accountChoices.isNotEmpty()) {
            val execution = form.execution ?: return EngineLoginResult.ServiceChanged("账号选择页面缺少 execution 字段")
            pendingAccountChoice = PendingAccountChoice(
                postUrl = response.finalUrl,
                execution = execution,
                choices = accountChoices,
                site = site,
            )
            return EngineLoginResult.NeedAccountChoice(
                AccountChoiceChallenge(
                    choices = accountChoices,
                    site = site,
                ),
            )
        }
        if (form.execution != null) {
            if (site != null && allowSiteLandingRetry) {
                return retrySiteLandingAfterCasRedirect(site)
            }
            if (form.hasCaptcha) {
                pendingLoginForm = PendingLoginForm(
                    postUrl = response.finalUrl,
                    execution = form.execution,
                    hasCaptcha = true,
                    failCount = form.failCount,
                    mfaEnabled = extractMfaEnabled(response.bodyText),
                    site = site,
                )
                pendingCaptchaCode = null
                return prepareCaptchaChallenge(site)
            }
            return EngineLoginResult.ServiceChanged("CAS 仍返回登录表单，未完成认证")
        }
        if (!response.isSuccessful) return EngineLoginResult.NetworkError("CAS 返回 HTTP ${response.code}")

        val username = pendingCredentials?.username ?: "cas"
        resetPending()
        return EngineLoginResult.Success(username)
    }

    private suspend fun retrySiteLandingAfterCasRedirect(site: SiteKey): EngineLoginResult {
        val landingUrl = siteLandingUrl(site) ?: return EngineLoginResult.ServiceChanged("CAS 仍返回登录表单，未完成认证")
        var landing = activeHttpClient.execute(HttpRequest(url = landingUrl))
        if (site == SiteKey.GRADE) {
            landing = followGradeCasOauthRedirects(landing)
            landing = exchangeGradeMobileJwappCodeCallback(landing)
        }
        if (CasHtmlParser.isSafetyVerifyPage(landing.bodyText)) {
            return prepareSafetyVerifyChallenge(landing, site)
        }
        val form = CasHtmlParser.parseLoginForm(landing.bodyText)
        if (form.execution != null || landing.finalUrl.contains("login.xjtu.edu.cn/cas", ignoreCase = true)) {
            return EngineLoginResult.ServiceChanged("CAS 仍返回登录表单，未完成认证")
        }
        if (!landing.isSuccessful && !(site == SiteKey.GRADE && landing.finalUrl.isMobileJwappCallbackWithToken())) {
            return EngineLoginResult.NetworkError("CAS 返回 HTTP ${landing.code}")
        }
        val username = pendingCredentials?.username ?: "cas"
        val siteHeaders = if (site == SiteKey.GRADE) {
            landing.finalUrl.mobileJwappAuthHeaders()
                ?: return EngineLoginResult.ServiceChanged("移动教务 OAuth 响应缺少 token")
        } else {
            null
        }
        resetPending()
        return if (siteHeaders != null) {
            EngineLoginResult.SiteSessionSuccess(username, site, siteHeaders)
        } else {
            EngineLoginResult.Success(username)
        }
    }

    private fun siteLandingUrl(site: SiteKey): String? =
        when (site) {
            SiteKey.SCHEDULE,
            SiteKey.JWXT,
            -> "https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do"
            SiteKey.GRADE -> "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234"
            SiteKey.CAMPUS_CARD -> "https://ncard.xjtu.edu.cn/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5"
            else -> null
        }

    private suspend fun followGradeCasOauthRedirects(initial: HttpResponse): HttpResponse {
        var response = initial
        repeat(MAX_GRADE_CAS_OAUTH_REDIRECTS) {
            if (!response.finalUrl.isCasOauthRedirectUrl()) return response
            response = activeHttpClient.execute(HttpRequest(url = response.finalUrl))
        }
        return response
    }

    private suspend fun exchangeGradeMobileJwappCodeCallback(response: HttpResponse): HttpResponse =
        if (response.finalUrl.isMobileJwappCallbackWithCode()) {
            activeHttpClient.execute(HttpRequest(url = response.finalUrl.asHttpsMobileJwappCallback()))
        } else {
            response
        }

    private fun String.isCasOauthRedirectUrl(): Boolean {
        val base = substringBefore("?")
        return base.equals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize",
            ignoreCase = true,
        ) || base.equals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize",
            ignoreCase = true,
        )
    }

    private fun String.isMobileJwappCallbackWithToken(): Boolean {
        if (!isMobileJwappAppUrl()) return false
        return urlParameter("token") != null
    }

    private fun String.isMobileJwappCallbackWithCode(): Boolean =
        isMobileJwappCallback() && urlParameter("code") != null

    private fun String.isMobileJwappCallback(): Boolean =
        startsWith("http://jwapp.xjtu.edu.cn/app/index", ignoreCase = true) ||
            startsWith("https://jwapp.xjtu.edu.cn/app/index", ignoreCase = true)

    private fun String.isMobileJwappAppUrl(): Boolean =
        startsWith("http://jwapp.xjtu.edu.cn/app/", ignoreCase = true) ||
            startsWith("https://jwapp.xjtu.edu.cn/app/", ignoreCase = true)

    private fun String.asHttpsMobileJwappCallback(): String =
        if (startsWith("http://", ignoreCase = true)) {
            "https://" + substringAfter("://")
        } else {
            this
        }

    private fun String.mobileJwappAuthHeaders(): Map<String, String>? {
        val token = urlParameter("token") ?: return null
        return mapOf(
            "Authorization" to token,
            "User-Agent" to MOBILE_JWAPP_UA,
        )
    }

    private fun String.urlParameter(name: String): String? {
        val query = substringAfter("?", missingDelimiterValue = "")
        val fragment = substringAfter("#", missingDelimiterValue = "")
        return listOf(query, fragment)
            .flatMap { it.split("&", "?") }
            .mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                val key = pieces.getOrNull(0)?.urlDecode() ?: return@mapNotNull null
                val value = pieces.getOrNull(1).orEmpty()
                if (key.equals(name, ignoreCase = true) && value.isNotBlank()) {
                    value.urlDecode()
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun String.urlDecode(): String {
        val bytes = mutableListOf<Byte>()
        val output = StringBuilder()
        var index = 0
        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                output.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        while (index < length) {
            when (val char = this[index]) {
                '%' -> {
                    val hex = substring(index + 1, index + 3)
                    bytes += hex.toInt(16).toByte()
                    index += 3
                }
                '+' -> {
                    flushBytes()
                    output.append(' ')
                    index++
                }
                else -> {
                    flushBytes()
                    output.append(char)
                    index++
                }
            }
        }
        flushBytes()
        return output.toString()
    }

    private fun HttpResponse.redirectLocation(): String? =
        headers.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun HttpResponse.casJsonError(): String? {
        if (!isSuccessful) return "CAS 验证接口返回 HTTP $code"
        val code = JsonField.int(bodyText, "code")
        if (code == 0) return null
        return JsonField.string(bodyText, "message") ?: "CAS 验证接口返回异常"
    }

    private fun resetPending() {
        pendingCredentials = null
        pendingEncryptedPassword = null
        pendingLoginForm = null
        pendingCaptchaCode = null
        pendingMfa = null
        pendingAccountChoice = null
    }

    private fun unsupportedBrowserAuth(): EngineLoginResult.ServiceChanged =
        EngineLoginResult.ServiceChanged("CAS 浏览器登录需要学校侧 callback/service ticket 交换支持")

    private data class PendingLoginForm(
        val postUrl: String,
        val execution: String,
        val hasCaptcha: Boolean,
        val failCount: Int,
        val mfaEnabled: Boolean,
        val site: SiteKey?,
    )

    private data class PendingMfa(
        val flow: MfaFlow,
        val state: String,
        val gid: String,
        val maskedPhone: String,
        val safety: PendingSafetyVerify?,
        val site: SiteKey?,
    )

    private data class PendingAccountChoice(
        val postUrl: String,
        val execution: String,
        val choices: List<AccountChoice>,
        val site: SiteKey?,
    )

    private data class PendingSafetyVerify(
        val secState: String,
        val execution: String,
        val eventId: String,
        val submitValue: String,
        val triggerUrl: String,
    )

    private data class SecurePhone(
        val gid: String,
        val maskedPhone: String,
    )

    private sealed interface MfaDetectResult {
        data class NotNeeded(val state: String) : MfaDetectResult
        data class NeedChallenge(val state: String) : MfaDetectResult
        data class Failed(val message: String) : MfaDetectResult
    }

    private fun MfaFlow.pathSegment(): String = when (this) {
        MfaFlow.MFA_DETECT -> "mfa"
        MfaFlow.SAFETY_VERIFY -> "sec"
    }

    private fun looksLikeAuthenticatedResponse(response: HttpResponse): Boolean =
        response.isSuccessful &&
            (response.bodyText.contains("登录成功", ignoreCase = true) ||
                (!response.finalUrl.contains("/cas/login", ignoreCase = true) &&
                    !response.bodyText.contains("cas/login", ignoreCase = true)))

    private fun extractMfaEnabled(html: String): Boolean {
        val match = Regex(
            """["']?mfaEnabled["']?\s*[:=]\s*["']?(true|false)["']?""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(html)
        return match?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true) ?: true
    }

    private fun extractAlertMessage(html: String): String? {
        val elAlert = Regex(
            """<el-alert\b[^>]*\btitle=["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
        if (!elAlert.isNullOrBlank()) return htmlDecode(elAlert)

        val danger = Regex(
            """<(?:div|span|p)\b[^>]*(?:alert-danger|errors|errorMessage)[^>]*>(.*?)</(?:div|span|p)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
        return danger?.stripTags()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun String.isVerificationFailureMessage(): Boolean =
        contains("验证码") ||
            contains("双因子") ||
            contains("二次验证") ||
            contains("安全验证") ||
            contains("Safety Verify", ignoreCase = true) ||
            contains("MFA", ignoreCase = true) ||
            contains("captcha", ignoreCase = true)

    private fun formPost(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String? = null,
    ): HttpRequest {
        val headers = mutableMapOf("Content-Type" to "application/x-www-form-urlencoded")
        if (referer != null) headers["Referer"] = referer
        return HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = headers,
            body = fields.joinToString("&") { (name, value) -> "${urlEncode(name)}=${urlEncode(value)}" }
                .encodeToByteArray(),
        )
    }

    private fun jsonPost(url: String, values: Map<String, String>): HttpRequest =
        HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = mapOf("Content-Type" to "application/json"),
            body = values.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
            }.encodeToByteArray(),
        )

    private fun urlEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val builder = StringBuilder()
        for (byte in bytes) {
            val int = byte.toInt() and 0xff
            val char = int.toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" -> builder.append(char)
                char == ' ' -> builder.append('+')
                else -> {
                    builder.append('%')
                    builder.append(HEX[int shr 4])
                    builder.append(HEX[int and 0x0f])
                }
            }
        }
        return builder.toString()
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun htmlDecode(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "").let(::htmlDecode)

    private object JsonField {
        fun string(json: String, name: String): String? =
            Regex(
                """"${Regex.escape(name)}"\s*:\s*"((?:\\.|[^"\\])*)"""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(json)?.groupValues?.getOrNull(1)?.jsonUnescape()

        fun boolean(json: String, name: String): Boolean? =
            Regex(
                """"${Regex.escape(name)}"\s*:\s*(true|false)""",
                setOf(RegexOption.IGNORE_CASE),
            ).find(json)?.groupValues?.getOrNull(1)?.equals("true", ignoreCase = true)

        fun int(json: String, name: String): Int? =
            Regex(
                """"${Regex.escape(name)}"\s*:\s*(-?\d+)""",
                setOf(RegexOption.IGNORE_CASE),
            ).find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()

        private fun String.jsonUnescape(): String =
            replace("\\\"", "\"")
                .replace("\\\\", "\\")
    }

    private companion object {
        private const val HEX = "0123456789ABCDEF"
        private const val MAX_GRADE_CAS_OAUTH_REDIRECTS = 4
        private const val MOBILE_JWAPP_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
