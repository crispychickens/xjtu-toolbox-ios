package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.network.DecoratingHttpClient
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.webvpn.WebVpnRequestRewriter

data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAtEpochMillis: Long? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
)

interface CookieStore {
    fun save(cookie: StoredCookie)
    fun loadForHost(host: String): List<StoredCookie>
    fun clear()
}

class InMemoryCookieStore : CookieStore {
    private val cookies = mutableListOf<StoredCookie>()

    override fun save(cookie: StoredCookie) {
        cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        cookies += cookie
    }

    override fun loadForHost(host: String): List<StoredCookie> {
        return cookies.filter { cookie ->
            host == cookie.domain || host.endsWith(".${cookie.domain}")
        }
    }

    override fun clear() {
        cookies.clear()
    }
}

data class SessionBackend(
    val accessMode: AccessMode,
    val httpClient: HttpClient,
    val cookieStore: CookieStore,
) {
    companion object {
        fun normal(httpClient: HttpClient, cookieStore: CookieStore = InMemoryCookieStore()): SessionBackend =
            SessionBackend(
                accessMode = AccessMode.NORMAL,
                httpClient = CookieAwareHttpClient(
                    delegate = httpClient,
                    cookieStore = cookieStore,
                ),
                cookieStore = cookieStore,
            )

        fun webvpn(httpClient: HttpClient, cookieStore: CookieStore = InMemoryCookieStore()): SessionBackend =
            SessionBackend(
                accessMode = AccessMode.WEBVPN,
                httpClient = DecoratingHttpClient(
                    delegate = CookieAwareHttpClient(
                        delegate = httpClient,
                        cookieStore = cookieStore,
                    ),
                    rewriters = listOf(WebVpnRequestRewriter()),
                ),
                cookieStore = cookieStore,
            )
    }
}
