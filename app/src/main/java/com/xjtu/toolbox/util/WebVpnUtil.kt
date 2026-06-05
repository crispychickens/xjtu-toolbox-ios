package com.xjtu.toolbox.util

import okhttp3.Interceptor
import okhttp3.Response
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec

/**
 * WebVPN URL 加密工具
 * 使用 AES-128-CFB 加密域名，将普通 URL 转换为 WebVPN 代理 URL
 * 算法位于 shared KMP 模块，Android 与 iOS 共用同一实现。
 */
object WebVpnUtil {

    private val codec = WebVpnUrlCodec()

    const val WEBVPN_LOGIN_URL = "https://webvpn.xjtu.edu.cn/login?cas_login=true"

    /**
     * 将普通 URL 转换为 WebVPN 代理 URL
     * 例: http://bkkq.xjtu.edu.cn/path → https://webvpn.xjtu.edu.cn/http/77726476706e697374686562657374218b8559...ef/path
     */
    fun getVpnUrl(url: String): String = codec.toVpnUrl(url)

    /**
     * 判断 URL 是否已是 WebVPN URL
     */
    fun isWebVpnUrl(url: String): Boolean = codec.isVpnUrl(url)

    /**
     * 判断 [finalUrl] 是否表示已成功登录目标站点（[targetHost] 不带 scheme，如 "lms.xjtu.edu.cn"），
     * 兼容直连 / WebVPN 两种模式。
     *
     * 规则：
     * 1. 必须不在 CAS 登录页（`login.xjtu.edu.cn/cas/login`）
     * 2. 直连模式：finalUrl 包含 targetHost
     * 3. WebVPN 模式：解出原始 URL 后包含 targetHost
     */
    fun isAtTargetSite(finalUrl: String, targetHost: String): Boolean {
        if (finalUrl.contains("login.xjtu.edu.cn/cas/login", ignoreCase = true)) return false
        if (finalUrl.contains(targetHost, ignoreCase = true) &&
            !finalUrl.contains("login.xjtu.edu.cn", ignoreCase = true)) return true
        if (isWebVpnUrl(finalUrl)) {
            val original = getOriginalUrl(finalUrl) ?: return false
            return original.contains(targetHost, ignoreCase = true) &&
                !original.contains("login.xjtu.edu.cn", ignoreCase = true)
        }
        return false
    }

    /**
     * 将 WebVPN 代理 URL 还原为原始 URL
     * 例: https://webvpn.xjtu.edu.cn/http-8086/77726476706e69...af/seat/ → http://rg.lib.xjtu.edu.cn:8086/seat/
     * 返回 null 表示无法解析
     */
    fun getOriginalUrl(vpnUrl: String): String? = codec.fromVpnUrl(vpnUrl)
}

/**
 * OkHttp 拦截器：自动将非 WebVPN URL 加密为 WebVPN 代理 URL
 * 添加到 OkHttpClient 后，所有 HTTP 请求自动通过 WebVPN 代理
 */
class WebVpnInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // 已经是 WebVPN URL，不重复加密
        if (WebVpnUtil.isWebVpnUrl(url)) {
            return chain.proceed(request)
        }

        // 注意：login.xjtu.edu.cn 也必须走 webvpn 代理！
        // 校外环境下 login.xjtu.edu.cn DNS 解析到内网 IP（202.117.x.x）不可达。
        // 通过 webvpn 反向代理是校外访问 CAS 的唯一通路。

        val vpnUrl = WebVpnUtil.getVpnUrl(url)
        val newRequest = request.newBuilder().url(vpnUrl).build()
        return chain.proceed(newRequest)
    }
}
