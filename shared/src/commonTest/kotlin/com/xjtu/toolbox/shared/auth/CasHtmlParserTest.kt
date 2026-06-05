package com.xjtu.toolbox.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CasHtmlParserTest {
    @Test
    fun parsesCasLoginFormFields() {
        val form = CasHtmlParser.parseLoginForm(
            """
            <form>
              <input name="execution" value="e1s1" />
              <input name="pwdDefaultEncryptSalt" value="rsa-key" />
            </form>
            """.trimIndent(),
        )

        assertEquals("e1s1", form.execution)
        assertEquals("rsa-key", form.rsaPublicKey)
    }

    @Test
    fun parsesInputFieldsRegardlessOfAttributeOrder() {
        val form = CasHtmlParser.parseLoginForm(
            """
            <form>
              <input value="e1s2" data-extra="1" name="execution" />
              <input value="rsa-key-2" name="pwdDefaultEncryptSalt" />
            </form>
            """.trimIndent(),
        )

        assertEquals("e1s2", form.execution)
        assertEquals("rsa-key-2", form.rsaPublicKey)
    }

    @Test
    fun detectsCaptchaFromCasFailCountInsteadOfPageScriptMentions() {
        val form = CasHtmlParser.parseLoginForm(
            """
            <form>
              <input name="execution" value="e1s1" />
              <input type="hidden" name="captcha" value="" />
              <input name="failN" value="0" />
            </form>
            <script>
              var captchaSkipN = "3";
              var __captchaImgUrl = "/cas/captcha.jpg";
            </script>
            """.trimIndent(),
        )

        assertFalse(form.hasCaptcha)
        assertEquals(0, form.failCount)
    }

    @Test
    fun currentCasNegativeFailCountDoesNotRequireCaptchaBeforeClientFailureState() {
        val form = CasHtmlParser.parseLoginForm(
            """
            <form>
              <input name="execution" value="e1s1" />
              <input type="hidden" name="captcha" value="" />
              <input name="failN" value="-1" />
            </form>
            <script>var captchaSkipN = "3";</script>
            """.trimIndent(),
        )

        assertFalse(form.hasCaptcha)
        assertEquals(-1, form.failCount)
    }

    @Test
    fun captchaSkipThresholdRequiresCaptcha() {
        val form = CasHtmlParser.parseLoginForm(
            """
            <form>
              <input name="execution" value="e1s1" />
              <input type="hidden" name="captcha" value="" />
              <input name="failN" value="3" />
            </form>
            <script>var captchaSkipN = "3";</script>
            """.trimIndent(),
        )

        assertTrue(form.hasCaptcha)
        assertEquals(3, form.failCount)
    }

    @Test
    fun detectsAndParsesSafetyVerifyForm() {
        val html = """
            <input value="sec-1" name="secState" />
            <input value="exec-1" name="execution" />
            <input name="_eventId" value="submit" />
            <input name="submit" value="Login1" />
        """.trimIndent()

        assertTrue(CasHtmlParser.isSafetyVerifyPage(html))
        val form = CasHtmlParser.parseSafetyVerifyForm(html)
        assertEquals("sec-1", form.secState)
        assertEquals("exec-1", form.execution)
        assertEquals("submit", form.eventId)
        assertEquals("Login1", form.submitValue)
    }

    @Test
    fun parsesAccountChoicesFromAccountWrapRadioMarkup() {
        val choices = CasHtmlParser.parseAccountChoices(
            """
            <div class="account-wrap">
              <div class="name">本科生账号</div>
              <el-radio label="3124000000"></el-radio>
            </div>
            <div class="account-wrap">
              <div class="name">研究生账号</div>
              <el-radio label="2224000000"></el-radio>
            </div>
            """.trimIndent(),
        )

        assertEquals(2, choices.size)
        assertEquals("3124000000", choices[0].id)
        assertEquals("本科生账号", choices[0].displayName)
        assertEquals(AccountType.UNDERGRADUATE, choices[0].accountType)
        assertEquals("2224000000", choices[1].id)
        assertEquals("研究生账号", choices[1].displayName)
        assertEquals(AccountType.POSTGRADUATE, choices[1].accountType)
    }

    @Test
    fun parsesAccountChoicesFromLegacyUsernameRadioMarkup() {
        val choices = CasHtmlParser.parseAccountChoices(
            """
            <label>
              <input type="radio" name="username" value="3124000000" />
              本科生账号
            </label>
            <label>
              <input value="2224000000" name="username" type="radio" />
              研究生账号
            </label>
            """.trimIndent(),
        )

        assertEquals(2, choices.size)
        assertEquals("3124000000", choices[0].id)
        assertEquals("本科生账号", choices[0].displayName)
        assertEquals(AccountType.UNDERGRADUATE, choices[0].accountType)
        assertEquals("2224000000", choices[1].id)
        assertEquals("研究生账号", choices[1].displayName)
        assertEquals(AccountType.POSTGRADUATE, choices[1].accountType)
    }

    @Test
    fun parsesHiddenUsernameAccountChoiceWithoutTypeHint() {
        val choices = CasHtmlParser.parseAccountChoices(
            """
            <form>
              <input type="hidden" name="username" value="3124000000" />
            </form>
            """.trimIndent(),
        )

        assertEquals(1, choices.size)
        assertEquals("3124000000", choices[0].id)
        assertEquals("3124000000", choices[0].displayName)
        assertEquals(AccountType.UNKNOWN, choices[0].accountType)
    }
}
