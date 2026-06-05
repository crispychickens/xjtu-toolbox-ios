package com.xjtu.toolbox.shared.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BrowserAuthCallbackParserTest {
    private val parser = BrowserAuthCallbackParser()
    private val challenge = BrowserAuthChallenge(
        loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
        callbackScheme = "xjtutoolbox",
        state = "state-1",
        site = SiteKey.JWXT,
    )

    @Test
    fun acceptsCasServiceTicketCallback() {
        val result = parser.parse(challenge, "xjtutoolbox://auth?state=state-1&ticket=ST-123")

        val callback = assertIs<BrowserAuthCallbackParseResult.Valid>(result).callback
        assertEquals("state-1", callback.state)
        assertEquals("ST-123", callback.ticket)
        assertEquals(null, callback.code)
        assertEquals(SiteKey.JWXT, callback.site)
    }

    @Test
    fun acceptsOAuthCodeCallback() {
        val result = parser.parse(challenge, "xjtutoolbox://auth?state=state-1&code=CODE%2F123")

        val callback = assertIs<BrowserAuthCallbackParseResult.Valid>(result).callback
        assertEquals(null, callback.ticket)
        assertEquals("CODE/123", callback.code)
    }

    @Test
    fun rejectsWrongScheme() {
        val result = parser.parse(challenge, "https://auth?state=state-1&ticket=ST-123")

        assertIs<BrowserAuthCallbackParseResult.Invalid>(result)
    }

    @Test
    fun rejectsWrongState() {
        val result = parser.parse(challenge, "xjtutoolbox://auth?state=other&ticket=ST-123")

        assertIs<BrowserAuthCallbackParseResult.Invalid>(result)
    }

    @Test
    fun rejectsCallbackWithoutTicketOrCode() {
        val result = parser.parse(challenge, "xjtutoolbox://auth?state=state-1")

        assertIs<BrowserAuthCallbackParseResult.Invalid>(result)
    }
}
