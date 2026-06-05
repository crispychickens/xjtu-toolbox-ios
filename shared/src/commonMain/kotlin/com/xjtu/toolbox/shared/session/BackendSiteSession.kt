package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.HeaderAuthenticatedSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse

class BackendSiteSession(
    override val site: SiteKey,
    private val backend: SessionBackend,
    private val authenticate: suspend (AuthContext) -> Map<String, String> = { emptyMap() },
) : HeaderAuthenticatedSiteSession {
    override val accessMode = backend.accessMode
    override var isAuthenticated: Boolean = false
        private set
    override var username: String? = null
        private set

    private var authHeaders: Map<String, String> = emptyMap()

    override suspend fun ensureAuthenticated(context: AuthContext): SiteSession {
        require(context.username.isNotBlank()) { "username is required" }
        val nextAuthHeaders = authenticate(context)
        adoptAuthenticatedHeaders(context, nextAuthHeaders)
        return this
    }

    override suspend fun adoptAuthenticatedHeaders(
        context: AuthContext,
        headers: Map<String, String>,
    ): SiteSession {
        require(context.username.isNotBlank()) { "username is required" }
        username = context.username
        authHeaders = headers
        isAuthenticated = true
        return this
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        check(isAuthenticated) { "session $site is not authenticated" }
        return backend.httpClient.execute(request.withHeaders(authHeaders + request.headers))
    }

    override suspend fun invalidate() {
        username = null
        authHeaders = emptyMap()
        isAuthenticated = false
    }

    private fun HttpRequest.withHeaders(nextHeaders: Map<String, String>): HttpRequest =
        copy(headers = nextHeaders)
}
