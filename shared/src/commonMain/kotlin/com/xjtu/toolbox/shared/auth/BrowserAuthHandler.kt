package com.xjtu.toolbox.shared.auth

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import kotlin.random.Random

interface BrowserAuthHandler {
    suspend fun begin(site: SiteKey?): EngineLoginResult
    suspend fun resume(callback: BrowserAuthCallback): EngineLoginResult
}

data class OfficialBrowserAuthConfig(
    val loginUrl: String = "https://login.xjtu.edu.cn/cas/login",
    val callbackScheme: String = "xjtutoolbox",
    val callbackUrl: String = "xjtutoolbox://auth",
    val stateProvider: BrowserAuthStateProvider = BrowserAuthStateProvider { randomBrowserAuthState() },
)

fun interface BrowserAuthStateProvider {
    fun nextState(site: SiteKey?): String
}

fun interface BrowserAuthArtifactExchanger {
    suspend fun exchange(callback: BrowserAuthCallback): BrowserAuthExchangeResult
}

sealed interface BrowserAuthExchangeResult {
    data class Success(val username: String) : BrowserAuthExchangeResult
    data class VerificationRejected(val message: String) : BrowserAuthExchangeResult
    data class NetworkError(val message: String) : BrowserAuthExchangeResult
    data class ServiceChanged(val message: String) : BrowserAuthExchangeResult
    data class UnknownError(val message: String) : BrowserAuthExchangeResult
}

class CasBrowserAuthHandler(
    private val config: OfficialBrowserAuthConfig = OfficialBrowserAuthConfig(),
    private val artifactExchanger: BrowserAuthArtifactExchanger,
) : BrowserAuthHandler {
    override suspend fun begin(site: SiteKey?): EngineLoginResult {
        val state = config.stateProvider.nextState(site)
        return EngineLoginResult.NeedBrowserAuth(
            BrowserAuthChallenge(
                loginUrl = appendQuery(
                    config.loginUrl,
                    "service" to appendQuery(config.callbackUrl, "state" to state),
                ),
                callbackScheme = config.callbackScheme,
                state = state,
                site = site,
            ),
        )
    }

    override suspend fun resume(callback: BrowserAuthCallback): EngineLoginResult {
        if (callback.ticket.isNullOrBlank() && callback.code.isNullOrBlank()) {
            return EngineLoginResult.VerificationRejected("网页登录回跳缺少 ticket 或 code")
        }
        return when (val exchanged = artifactExchanger.exchange(callback)) {
            is BrowserAuthExchangeResult.Success -> EngineLoginResult.Success(exchanged.username)
            is BrowserAuthExchangeResult.VerificationRejected -> EngineLoginResult.VerificationRejected(exchanged.message)
            is BrowserAuthExchangeResult.NetworkError -> EngineLoginResult.NetworkError(exchanged.message)
            is BrowserAuthExchangeResult.ServiceChanged -> EngineLoginResult.ServiceChanged(exchanged.message)
            is BrowserAuthExchangeResult.UnknownError -> EngineLoginResult.UnknownError(exchanged.message)
        }
    }

    private fun appendQuery(url: String, parameter: Pair<String, String>): String {
        val separator = if ('?' in url) "&" else "?"
        return "$url$separator${urlEncode(parameter.first)}=${urlEncode(parameter.second)}"
    }

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

    private companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}

class CasServiceTicketExchanger(
    private val httpClient: HttpClient,
    private val config: OfficialBrowserAuthConfig = OfficialBrowserAuthConfig(),
    private val serviceValidateUrl: String = "https://login.xjtu.edu.cn/cas/serviceValidate",
) : BrowserAuthArtifactExchanger {
    override suspend fun exchange(callback: BrowserAuthCallback): BrowserAuthExchangeResult {
        val ticket = callback.ticket
            ?: return BrowserAuthExchangeResult.VerificationRejected("网页登录回跳缺少 ticket")
        val serviceUrl = appendQuery(config.callbackUrl, "state" to callback.state)
        val response = httpClient.execute(
            HttpRequest(
                url = appendQuery(
                    serviceValidateUrl,
                    "service" to serviceUrl,
                    "ticket" to ticket,
                ),
            ),
        )
        if (!response.isSuccessful) {
            return BrowserAuthExchangeResult.NetworkError("CAS ticket 校验返回 HTTP ${response.code}")
        }
        val failure = casElement(response.bodyText, "authenticationFailure")
        if (!failure.isNullOrBlank()) {
            return BrowserAuthExchangeResult.VerificationRejected(failure.stripTags().trim())
        }
        val username = casElement(response.bodyText, "user")
            ?.stripTags()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return BrowserAuthExchangeResult.ServiceChanged("CAS ticket 校验返回缺少 user")
        return BrowserAuthExchangeResult.Success(username)
    }
}

private fun appendQuery(url: String, vararg parameters: Pair<String, String>): String {
    val separator = if ('?' in url) "&" else "?"
    return url + separator + parameters.joinToString("&") { (name, value) ->
        "${urlEncode(name)}=${urlEncode(value)}"
    }
}

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

private fun casElement(xml: String, localName: String): String? =
    Regex(
        """<(?:(?:\w+):)?${Regex.escape(localName)}\b[^>]*>(.*?)</(?:(?:\w+):)?${Regex.escape(localName)}>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(xml)?.groupValues?.getOrNull(1)?.xmlDecode()

private fun String.stripTags(): String =
    replace(Regex("<[^>]+>"), "")

private fun String.xmlDecode(): String =
    replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

private fun randomBrowserAuthState(): String =
    "state-${Random.nextLong().toString(36)}-${Random.nextLong().toString(36)}"

private const val HEX = "0123456789ABCDEF"
