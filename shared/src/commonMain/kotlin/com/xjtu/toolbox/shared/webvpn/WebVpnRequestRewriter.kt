package com.xjtu.toolbox.shared.webvpn

import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.RequestRewriter

class WebVpnRequestRewriter(
    private val codec: WebVpnUrlCodec = WebVpnUrlCodec(),
) : RequestRewriter {
    override fun rewrite(request: HttpRequest): HttpRequest {
        val nextUrl = if (codec.isVpnUrl(request.url)) request.url else codec.toVpnUrl(request.url)
        return request.copy(
            url = nextUrl,
            headers = request.headers.mapValues { (name, value) ->
                if (name.equals("Referer", ignoreCase = true) || name.equals("Referrer", ignoreCase = true)) {
                    rewriteHeaderUrl(value)
                } else {
                    value
                }
            },
        )
    }

    private fun rewriteHeaderUrl(value: String): String =
        if (codec.isVpnUrl(value)) value else codec.toVpnUrl(value)
}
