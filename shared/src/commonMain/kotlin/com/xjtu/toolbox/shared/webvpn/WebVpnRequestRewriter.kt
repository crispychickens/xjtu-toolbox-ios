package com.xjtu.toolbox.shared.webvpn

import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.RequestRewriter

class WebVpnRequestRewriter(
    private val codec: WebVpnUrlCodec = WebVpnUrlCodec(),
) : RequestRewriter {
    override fun rewrite(request: HttpRequest): HttpRequest {
        if (codec.isVpnUrl(request.url)) return request
        return request.withUrl(codec.toVpnUrl(request.url))
    }
}
