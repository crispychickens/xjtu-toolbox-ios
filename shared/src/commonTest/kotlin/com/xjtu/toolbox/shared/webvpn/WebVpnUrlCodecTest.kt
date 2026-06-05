package com.xjtu.toolbox.shared.webvpn

import kotlin.test.Test
import kotlin.test.assertEquals

class WebVpnUrlCodecTest {
    @Test
    fun convertsOriginalUrlToWebVpnUrlAndBackThroughHostCodecSeam() {
        val codec = WebVpnUrlCodec(HexHostCodec())
        val raw = "http://rg.lib.xjtu.edu.cn:8086/seat/index"

        val vpn = codec.toVpnUrl(raw)

        assertEquals(raw, codec.fromVpnUrl(vpn))
    }

    @Test
    fun convertsOriginalUrlToWebVpnUrlAndBackWithXjtuCodec() {
        val codec = WebVpnUrlCodec()
        val raw = "https://jwxt.xjtu.edu.cn/api/v2/system/term-info"

        val vpn = codec.toVpnUrl(raw)

        assertEquals(raw, codec.fromVpnUrl(vpn))
    }
}
