package com.xjtu.toolbox.shared.network

import com.xjtu.toolbox.shared.session.InMemoryCookieStore
import com.xjtu.toolbox.shared.session.SessionBackend
import com.xjtu.toolbox.shared.session.StoredCookie
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebVpnNetworkTest {
    @Test
    fun webVpnBackendRewritesEveryNonVpnUrlIncludingCasHost() = runTest {
        val recording = RecordingHttpClient()
        val backend = SessionBackend.webvpn(recording)

        backend.httpClient.execute(HttpRequest("https://login.xjtu.edu.cn/cas/login"))

        val sentUrl = recording.requests.single().url
        assertTrue(WebVpnUrlCodec().isVpnUrl(sentUrl))
        assertEquals("https://login.xjtu.edu.cn/cas/login", WebVpnUrlCodec().fromVpnUrl(sentUrl))
    }

    @Test
    fun webVpnBackendDoesNotDoubleRewriteVpnUrl() = runTest {
        val recording = RecordingHttpClient()
        val backend = SessionBackend.webvpn(recording)
        val vpnUrl = WebVpnUrlCodec().toVpnUrl("https://jwxt.xjtu.edu.cn/api/v2/system/term-info")

        backend.httpClient.execute(HttpRequest(vpnUrl))

        assertEquals(vpnUrl, recording.requests.single().url)
    }

    @Test
    fun cookieStoreKeepsDomainsIsolated() {
        val store = InMemoryCookieStore()
        store.save(StoredCookie(name = "TGC", value = "normal", domain = "login.xjtu.edu.cn"))
        store.save(StoredCookie(name = "WEngine", value = "vpn", domain = "webvpn.xjtu.edu.cn"))

        assertEquals(listOf("normal"), store.loadForHost("login.xjtu.edu.cn").map { it.value })
        assertEquals(listOf("vpn"), store.loadForHost("webvpn.xjtu.edu.cn").map { it.value })
    }

    @Test
    fun normalBackendSendsStoredCookiesAndPersistsSetCookie() = runTest {
        val recording = RecordingHttpClient(
            response = HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                headers = mapOf("Set-Cookie" to "CASTGC=TGT-1; Path=/cas; Max-Age=60; HttpOnly; Secure"),
            ),
        )
        val store = InMemoryCookieStore()
        store.save(StoredCookie(name = "SESSION", value = "abc", domain = "login.xjtu.edu.cn", path = "/cas"))
        val backend = SessionBackend.normal(recording, store)

        backend.httpClient.execute(HttpRequest("https://login.xjtu.edu.cn/cas/login"))

        assertEquals("SESSION=abc", recording.requests.single().headers["Cookie"])
        assertEquals(
            listOf("SESSION=abc", "CASTGC=TGT-1"),
            store.loadForHost("login.xjtu.edu.cn").map { "${it.name}=${it.value}" },
        )
    }

    @Test
    fun normalBackendDoesNotSendSecureCookiesOverHttpOrWrongPath() = runTest {
        val recording = RecordingHttpClient()
        val store = InMemoryCookieStore()
        store.save(StoredCookie(name = "SECURE", value = "1", domain = "login.xjtu.edu.cn", path = "/", secure = true))
        store.save(StoredCookie(name = "PATH", value = "2", domain = "login.xjtu.edu.cn", path = "/cas", secure = false))
        val backend = SessionBackend.normal(recording, store)

        backend.httpClient.execute(HttpRequest("http://login.xjtu.edu.cn/public"))

        assertEquals(null, recording.requests.single().headers["Cookie"])
    }

    @Test
    fun normalBackendRespectsCookiePathBoundary() = runTest {
        val recording = RecordingHttpClient()
        val store = InMemoryCookieStore()
        store.save(StoredCookie(name = "CASTGC", value = "1", domain = "login.xjtu.edu.cn", path = "/cas", secure = false))
        val backend = SessionBackend.normal(recording, store)

        backend.httpClient.execute(HttpRequest("http://login.xjtu.edu.cn/caslogin"))

        assertEquals(null, recording.requests.single().headers["Cookie"])
    }

    @Test
    fun webVpnBackendRewritesBeforeCookieLookup() = runTest {
        val recording = RecordingHttpClient()
        val store = InMemoryCookieStore()
        store.save(StoredCookie(name = "WEngine", value = "vpn", domain = "webvpn.xjtu.edu.cn"))
        val backend = SessionBackend.webvpn(recording, store)

        backend.httpClient.execute(HttpRequest("https://login.xjtu.edu.cn/cas/login"))

        val sent = recording.requests.single()
        assertTrue(WebVpnUrlCodec().isVpnUrl(sent.url))
        assertEquals("WEngine=vpn", sent.headers["Cookie"])
    }
}

private class RecordingHttpClient(
    private val response: HttpResponse? = null,
) : HttpClient {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return response ?: HttpResponse(code = 200, finalUrl = request.url)
    }
}
