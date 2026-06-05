package com.xjtu.toolbox.shared.auth

sealed interface BrowserAuthCallbackParseResult {
    data class Valid(val callback: BrowserAuthCallback) : BrowserAuthCallbackParseResult
    data class Invalid(val reason: String) : BrowserAuthCallbackParseResult
}

class BrowserAuthCallbackParser {
    fun parse(challenge: BrowserAuthChallenge, callbackUrl: String): BrowserAuthCallbackParseResult {
        val callbackScheme = callbackUrl.substringBefore(':', missingDelimiterValue = "")
        if (!callbackScheme.equals(challenge.callbackScheme, ignoreCase = true)) {
            return BrowserAuthCallbackParseResult.Invalid("网页登录回跳 scheme 不匹配")
        }

        val parameters = queryParameters(callbackUrl)
        val state = parameters["state"] ?: return BrowserAuthCallbackParseResult.Invalid("网页登录回跳缺少 state")
        if (state != challenge.state) {
            return BrowserAuthCallbackParseResult.Invalid("网页登录回跳 state 不匹配")
        }

        val error = parameters["error"]
        if (!error.isNullOrBlank()) {
            return BrowserAuthCallbackParseResult.Invalid(error)
        }

        val ticket = parameters["ticket"]?.takeIf { it.isNotBlank() }
        val code = parameters["code"]?.takeIf { it.isNotBlank() }
        if (ticket == null && code == null) {
            return BrowserAuthCallbackParseResult.Invalid("网页登录回跳缺少 ticket 或 code")
        }

        return BrowserAuthCallbackParseResult.Valid(
            BrowserAuthCallback(
                rawUrl = callbackUrl,
                state = state,
                ticket = ticket,
                code = code,
                site = challenge.site,
            ),
        )
    }

    private fun queryParameters(url: String): Map<String, String> {
        val queryStart = url.indexOf('?')
        if (queryStart == -1 || queryStart == url.lastIndex) return emptyMap()

        val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
        val query = if (fragmentStart == -1) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }

        return query.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=').formDecode()
                val value = pair.substringAfter('=', missingDelimiterValue = "").formDecode()
                key to value
            }
    }

    private fun String.formDecode(): String {
        if ('%' !in this && '+' !in this) return this
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < length) {
            when (val char = this[index]) {
                '+' -> {
                    bytes += ' '.code.toByte()
                    index += 1
                }
                '%' -> {
                    val hex = substring(index + 1, (index + 3).coerceAtMost(length))
                    val decoded = if (hex.length == 2) hex.toIntOrNull(16) else null
                    if (decoded != null) {
                        bytes += decoded.toByte()
                        index += 3
                    } else {
                        bytes += char.code.toByte()
                        index += 1
                    }
                }
                else -> {
                    bytes += char.code.toByte()
                    index += 1
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
