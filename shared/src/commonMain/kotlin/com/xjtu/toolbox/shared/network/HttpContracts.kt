package com.xjtu.toolbox.shared.network

enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH,
    HEAD,
}

data class HttpRequest(
    val url: String,
    val method: HttpMethod = HttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    fun withUrl(nextUrl: String): HttpRequest = copy(url = nextUrl)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return url == other.url &&
            method == other.method &&
            headers == other.headers &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }
}
data class HttpResponse(
    val code: Int,
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val bodyText: String = "",
) {
    val isSuccessful: Boolean get() = code in 200..299
}

interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}

interface RequestRewriter {
    fun rewrite(request: HttpRequest): HttpRequest
}

class DecoratingHttpClient(
    private val delegate: HttpClient,
    private val rewriters: List<RequestRewriter>,
) : HttpClient {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val rewritten = rewriters.fold(request) { current, rewriter -> rewriter.rewrite(current) }
        return delegate.execute(rewritten)
    }
}
