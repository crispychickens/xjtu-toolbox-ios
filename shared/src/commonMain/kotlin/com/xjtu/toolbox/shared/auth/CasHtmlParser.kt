package com.xjtu.toolbox.shared.auth

data class CasLoginForm(
    val execution: String?,
    val rsaPublicKey: String?,
    val hasCaptcha: Boolean,
    val failCount: Int,
)

data class SafetyVerifyForm(
    val secState: String?,
    val execution: String?,
    val eventId: String,
    val submitValue: String,
)

object CasHtmlParser {
    fun parseLoginForm(html: String): CasLoginForm {
        val failCount = inputValue(html, "failN")?.toIntOrNull() ?: 0
        return CasLoginForm(
            execution = inputValue(html, "execution"),
            rsaPublicKey = inputValue(html, "pwdDefaultEncryptSalt")
                ?: inputValue(html, "publicKey")
                ?: publicKeyFromScript(html),
            hasCaptcha = requiresCaptcha(html, failCount),
            failCount = failCount,
        )
    }

    fun isSafetyVerifyPage(html: String): Boolean {
        return html.contains("secState", ignoreCase = true) ||
            html.contains("Safety Verify", ignoreCase = true) ||
            html.contains("/cas/sec/", ignoreCase = true)
    }

    fun parseSafetyVerifyForm(html: String): SafetyVerifyForm {
        return SafetyVerifyForm(
            secState = inputValue(html, "secState"),
            execution = inputValue(html, "execution"),
            eventId = inputValue(html, "_eventId") ?: "submit",
            submitValue = inputValue(html, "submit") ?: "Login1",
        )
    }

    fun parseAccountChoices(html: String): List<AccountChoice> {
        val choices = mutableListOf<AccountChoice>()

        val accountWrapPattern = Regex(
            """<div\b[^>]*\bclass\s*=\s*["'][^"']*account-wrap[^"']*["'][^>]*>(.*?)<el-radio\b([^>]*)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        accountWrapPattern.findAll(html).forEach { match ->
            val beforeRadio = match.groupValues[1]
            val radioTag = match.groupValues[2]
            val id = radioTag.attributeValue("label")
            if (!id.isNullOrBlank()) {
                choices += AccountChoice(
                    id = id,
                    displayName = accountDisplayName(beforeRadio, id),
                    accountType = inferAccountType(beforeRadio),
                )
            }
        }
        if (choices.isNotEmpty()) return choices.distinctBy { it.id }

        val inputPattern = Regex(
            """<input\b[^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        inputPattern.findAll(html).forEach { match ->
            val tag = match.value
            val name = tag.attributeValue("name")
            val type = tag.attributeValue("type")
            val id = tag.attributeValue("value")
            if (
                name.equals("username", ignoreCase = true) &&
                (type.equals("radio", ignoreCase = true) || type.equals("hidden", ignoreCase = true)) &&
                !id.isNullOrBlank()
            ) {
                val context = accountInputContext(html, match.range)
                choices += AccountChoice(
                    id = id,
                    displayName = accountDisplayNameFromInputContext(context, id),
                    accountType = inferAccountType(context),
                )
            }
        }
        return choices.distinctBy { it.id }
    }

    private fun inputValue(html: String, name: String): String? {
        val inputPattern = Regex(
            """<input\b[^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return inputPattern.findAll(html)
            .map { it.value }
            .firstOrNull { inputTag ->
                inputTag.attributeValue("name")?.equals(name, ignoreCase = true) == true
            }
            ?.attributeValue("value")
    }

    private fun requiresCaptcha(html: String, failCount: Int): Boolean {
        val skipCount = Regex(
            """\bcaptchaSkipN\b\s*=\s*["']?(-?\d+)["']?""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_CAPTCHA_SKIP_COUNT
        if (failCount >= skipCount) return true

        val captchaInput = Regex(
            """<input\b[^>]*\bname\s*=\s*["']captcha["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.value ?: return false
        return captchaInput.attributeValue("type")?.equals("hidden", ignoreCase = true) != true
    }

    private fun accountDisplayName(html: String, fallback: String): String {
        val name = Regex(
            """<div\b[^>]*\bclass\s*=\s*["'][^"']*\bname\b[^"']*["'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)?.stripTags()?.trim()
        return name?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun accountInputContext(html: String, inputRange: IntRange): String {
        val before = html.substring(0, inputRange.first)
        val labelOpen = Regex(
            """<label\b[^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(before).lastOrNull()
        val labelCloseBefore = before.lastIndexOf("</label>", ignoreCase = true)
        val after = html.substring(inputRange.last + 1)
        val labelCloseAfter = Regex(
            """</label>""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(after)

        if (
            labelOpen != null &&
            labelCloseBefore < labelOpen.range.first &&
            labelCloseAfter != null
        ) {
            val endExclusive = inputRange.last + 1 + labelCloseAfter.range.last + 1
            return html.substring(labelOpen.range.first, endExclusive)
        }

        val start = maxOf(0, inputRange.first - INPUT_CONTEXT_RADIUS)
        val end = minOf(html.length, inputRange.last + 1 + INPUT_CONTEXT_RADIUS)
        return html.substring(start, end)
    }

    private fun accountDisplayNameFromInputContext(context: String, fallback: String): String {
        val text = context.stripTags()
            .replace(Regex("""\s+"""), " ")
            .replace(fallback, "")
            .trim()
        return text.takeIf { it.isNotBlank() && it.length <= MAX_ACCOUNT_DISPLAY_NAME_LENGTH } ?: fallback
    }

    private fun inferAccountType(text: String): AccountType =
        when {
            text.contains("本科") -> AccountType.UNDERGRADUATE
            text.contains("研究") || text.contains("硕士") || text.contains("博士") -> AccountType.POSTGRADUATE
            else -> AccountType.UNKNOWN
        }

    private fun String.attributeValue(name: String): String? {
        val pattern = Regex(
            """\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.find(this)?.groupValues?.getOrNull(1)
    }

    private fun publicKeyFromScript(html: String): String? {
        val pattern = Regex(
            """(?:rsaPublicKey|publicKey)\s*[:=]\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.find(html)?.groupValues?.getOrNull(1)
    }

    private fun htmlDecode(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "").let(::htmlDecode)

    private const val INPUT_CONTEXT_RADIUS = 120
    private const val MAX_ACCOUNT_DISPLAY_NAME_LENGTH = 40
    private const val DEFAULT_CAPTCHA_SKIP_COUNT = 3
}
