package com.xjtu.toolbox.shared.auth

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CasAuthEngineTest {
    @Test
    fun loginPostsCasFormWithEncryptedPasswordWithoutMfa() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)

        assertIs<EngineLoginResult.Success>(result)
        assertEquals("https://login.xjtu.edu.cn/cas/login", client.requests[0].url)
        val post = client.requests[1]
        assertEquals(HttpMethod.POST, post.method)
        assertEquals("username=3124000000", post.bodyString().split("&")[0])
        assertTrue(post.bodyString().contains("password=__RSA__secret%40rsa-key"))
        assertTrue(post.bodyString().contains("execution=e1s1"))
        assertTrue(post.bodyString().contains("fpVisitorId=visitor-1"))
        assertFalse(post.bodyString().contains("mfaState="))
        assertFalse(post.bodyString().contains("trustAgent="))
    }

    @Test
    fun loginAcceptsCasSuccessPageWithoutExecutionWhenTgcAlreadyExists() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = "<html><title>登录成功 - 西安交通大学统一身份认证网关</title></html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)

        assertIs<EngineLoginResult.Success>(result)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun siteVerificationAcceptsAuthenticatedPageWithoutExecutionAsSiteSessionSuccess() = runTest {
        val client = QueueHttpClient()
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.LIBRARY,
            context = SiteVerificationContext(
                finalUrl = "http://rg.lib.xjtu.edu.cn:8086/seat/",
                bodyText = "<html><title>登录成功 - 西安交通大学统一身份认证网关</title></html>",
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.LIBRARY, success.site)
        assertEquals(emptyMap(), success.headers)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun captchaLoginFetchesImageAndSubmitsUserCode() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = captchaLoginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/captcha.jpg",
                bodyText = "captcha-image-base64",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val second = engine.submitCaptcha("a1b2")

        val challenge = assertIs<EngineLoginResult.NeedCaptcha>(first).challenge
        assertEquals("captcha-image-base64", challenge.imageBase64)
        assertIs<EngineLoginResult.Success>(second)
        assertEquals("https://login.xjtu.edu.cn/cas/captcha.jpg", client.requests[1].url)
        val post = client.requests[2].bodyString()
        assertTrue(post.contains("captcha=a1b2"))
        assertTrue(post.contains("failN=3"))
        assertTrue(post.contains("password=__RSA__secret%40rsa-key"))
    }

    @Test
    fun captchaLoginRunsMfaDetectAfterUserCodeWithoutExtraLoop() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = captchaLoginPage(mfaEnabled = true),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/captcha.jpg",
                bodyText = "captcha-image-base64",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":true}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/initByType/securephone",
                bodyText = """{"code":0,"data":{"gid":"gid-1","securePhone":"188****0000"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
                bodyText = """{"code":0}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                bodyText = """{"code":0,"data":{"status":"2"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val second = engine.submitCaptcha("a1b2")
        val third = engine.submitMfa("123456")

        assertIs<EngineLoginResult.NeedCaptcha>(first)
        val mfa = assertIs<EngineLoginResult.NeedMfa>(second).challenge
        assertEquals("188****0000", mfa.maskedPhone)
        assertIs<EngineLoginResult.Success>(third)
        assertEquals("https://login.xjtu.edu.cn/cas/mfa/detect", client.requests[2].url)
        assertTrue(client.requests[6].bodyString().contains("captcha=a1b2"))
        assertTrue(client.requests[6].bodyString().contains("mfaState=mfa-state"))
    }

    @Test
    fun loginDetectsMfaSendsCodeAndFinishesAfterSubmit() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = true),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":true}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/initByType/securephone",
                bodyText = """{"code":0,"data":{"gid":"gid-1","securePhone":"188****0000"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
                bodyText = """{"code":0}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                bodyText = """{"code":0,"data":{"status":"2"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val second = engine.submitMfa("123456")

        val challenge = assertIs<EngineLoginResult.NeedMfa>(first).challenge
        assertEquals(MfaFlow.MFA_DETECT, challenge.flow)
        assertEquals("188****0000", challenge.maskedPhone)
        assertIs<EngineLoginResult.Success>(second)
        assertEquals("https://login.xjtu.edu.cn/cas/mfa/detect", client.requests[1].url)
        assertTrue(client.requests[1].bodyString().contains("password=__RSA__secret%40rsa-key"))
        assertEquals("https://login.xjtu.edu.cn/attest/api/guard/securephone/send", client.requests[3].url)
        assertEquals("""{"gid":"gid-1"}""", client.requests[3].bodyString())
        assertEquals("https://login.xjtu.edu.cn/attest/api/guard/securephone/valid", client.requests[4].url)
        assertEquals("""{"gid":"gid-1","code":"123456"}""", client.requests[4].bodyString())
        assertTrue(client.requests[5].bodyString().contains("mfaState=mfa-state"))
        assertTrue(client.requests[5].bodyString().contains("trustAgent=true"))
    }

    @Test
    fun mfaDetectNotNeededStillPostsReturnedStateWithBlankTrustAgent() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = true),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)

        assertIs<EngineLoginResult.Success>(result)
        val post = client.requests[2].bodyString()
        assertTrue(post.contains("mfaState=mfa-state"))
        assertTrue(post.contains("trustAgent="))
    }

    @Test
    fun casLoginRedirectAfterSubmitIsTreatedAsSuccess() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp",
                bodyText = captchaLoginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/captcha.jpg",
                bodyText = "captcha-image-base64",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/*default/index.do",
                headers = mapOf(
                    "Location" to "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/*default/index.do",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val second = engine.submitCaptcha("a1b2")

        assertIs<EngineLoginResult.NeedCaptcha>(first)
        assertIs<EngineLoginResult.Success>(second)
    }

    @Test
    fun accountChoiceIsReturnedAndSelectedWithoutBlindFailure() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = accountChoicePage(),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val challenge = assertIs<EngineLoginResult.NeedAccountChoice>(first).challenge
        val second = engine.submitAccountChoice("postgraduate-label")

        assertEquals(2, challenge.choices.size)
        assertEquals(AccountType.UNDERGRADUATE, challenge.choices[0].accountType)
        assertEquals(AccountType.POSTGRADUATE, challenge.choices[1].accountType)
        assertIs<EngineLoginResult.Success>(second)
        val choicePost = client.requests[2].bodyString()
        assertTrue(choicePost.contains("execution=choice-exec"))
        assertTrue(choicePost.contains("username=postgraduate-label"))
        assertTrue(choicePost.contains("useDefault=false"))
        assertTrue(choicePost.contains("fpVisitorId=visitor-1"))
    }

    @Test
    fun invalidAccountChoiceCanBeCorrectedWithoutRestartingLogin() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = accountChoicePage(),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val rejected = engine.submitAccountChoice("missing-choice")
        val recovered = engine.submitAccountChoice("undergraduate-label")

        assertIs<EngineLoginResult.VerificationRejected>(rejected)
        assertIs<EngineLoginResult.Success>(recovered)
        assertEquals(3, client.requests.size)
        assertTrue(client.requests[2].bodyString().contains("username=undergraduate-label"))
    }

    @Test
    fun initialSafetyVerifyPageBecomesSafetyMfaAndPostsBackToTriggerUrl() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwxt",
                bodyText = safetyPage(),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/sec/initByType/securephone",
                bodyText = """{"code":0,"data":{"gid":"gid-sec","securePhone":"199****0000"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
                bodyText = """{"code":0}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                bodyText = """{"code":0,"data":{"status":"2"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.WEBVPN)
        val second = engine.submitMfa("654321")

        val challenge = assertIs<EngineLoginResult.NeedMfa>(first).challenge
        assertEquals(MfaFlow.SAFETY_VERIFY, challenge.flow)
        assertEquals("199****0000", challenge.maskedPhone)
        assertIs<EngineLoginResult.Success>(second)
        val safetyPost = client.requests[4]
        assertEquals("https://login.xjtu.edu.cn/cas/login?service=jwxt", safetyPost.url)
        assertTrue(safetyPost.bodyString().contains("secState=sec-1"))
        assertTrue(safetyPost.bodyString().contains("execution=exec-sec"))
        assertTrue(safetyPost.bodyString().contains("_eventId=submit"))
        assertTrue(safetyPost.bodyString().contains("submit=Login1"))
        assertTrue(safetyPost.bodyString().contains("fpVisitorId=visitor-1"))
    }

    @Test
    fun siteVerificationRetriesJwxtLandingAfterCasRedirectBeforeSuccess() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp",
                headers = mapOf(
                    "Location" to "https://jwxt.xjtu.edu.cn/jwapp/sys/wdkb/*default/index.do?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do",
                bodyText = "<html>教务系统</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.SCHEDULE,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.SCHEDULE, success.site)
        assertEquals(emptyMap(), success.headers)
        assertEquals("https://login.xjtu.edu.cn/cas/mfa/detect", client.requests[0].url)
        assertEquals("https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp", client.requests[1].url)
        assertEquals("https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do", client.requests[2].url)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun librarySiteVerificationClaimsSessionWithoutHeadersAfterLandingSuccess() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=library",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "http://rg.lib.xjtu.edu.cn:8086/seat/",
                bodyText = "<html><div class=\"btn-group\"><span>seat</span></div></html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.LIBRARY,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=library",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.LIBRARY, success.site)
        assertEquals(emptyMap(), success.headers)
        assertEquals("http://rg.lib.xjtu.edu.cn:8086/seat/", client.requests[2].url)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun gradeSiteVerificationRetriesRegisteredMobileJwappLandingAfterCasRedirect() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code&client_id=1370",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "http://jwapp.xjtu.edu.cn/app/index?code=code-1&state=1234",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwapp.xjtu.edu.cn/app/mobile?token=token-1&state=1234",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.GRADE,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.GRADE, success.site)
        assertEquals("token-1", success.headers["Authorization"])
        assertTrue(success.headers["User-Agent"]?.contains("Mobile Safari") == true)
        assertEquals(
            "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234",
            client.requests[2].url,
        )
        assertEquals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
            client.requests[3].url,
        )
        assertEquals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code&client_id=1370",
            client.requests[4].url,
        )
        assertEquals(
            "https://jwapp.xjtu.edu.cn/app/index?code=code-1&state=1234",
            client.requests[5].url,
        )
        assertEquals(6, client.requests.size)
    }

    @Test
    fun couponSiteVerificationExchangesOauthCodeForSessionHeaders() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=coupon",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://egc.xjtu.edu.cn/page/cas/receiveCas.html?code=CODE-1&userType=student&employeeNo=3124000000",
                bodyText = "<html><title>我的卡券</title></html>",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://egc.xjtu.edu.cn/sso/login",
                bodyText = """{"code":200,"data":{"access_token":"eyJcoupon.token","username":"student"}}""",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.COUPON,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=coupon",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.COUPON, success.site)
        assertEquals("eyJcoupon.token", success.headers["Authorization"])
        assertTrue(success.headers["User-Agent"]?.contains("Chrome") == true)
        assertEquals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code&client_id=1596&redirect_uri=https%3A%2F%2Forg.xjtu.edu.cn%2Fopenplatform%2Foauth%2Fauthorizesw%3Fredirect_uri%3Dbase64aHR0cHM6Ly9lZ2MueGp0dS5lZHUuY24vcGFnZS9jYXMvcmVjZWl2ZUNhcy5odG1sP3ZlcnNpb249U0FGVF9WRVJTSU9O&state=1995",
            client.requests[2].url,
        )
        val tokenRequest = client.requests[3]
        assertEquals(HttpMethod.POST, tokenRequest.method)
        assertTrue(tokenRequest.url.contains("https://egc.xjtu.edu.cn/sso/login?code=CODE-1"))
        assertTrue(tokenRequest.url.contains("userType=student"))
        assertTrue(tokenRequest.url.contains("employeeNo=3124000000"))
        assertEquals(4, client.requests.size)
    }

    @Test
    fun siteVerificationFallsBackToCasRedirectWhenJwxtLandingStillReturnsLoginPage() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = true),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do",
                bodyText = "<html>教务系统</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.SCHEDULE,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=https%3A%2F%2Fjwxt.xjtu.edu.cn%2Fjwapp",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.SCHEDULE, success.site)
        assertEquals(emptyMap(), success.headers)
        assertEquals("https://jwxt.xjtu.edu.cn/jwapp/sys/homeapp/index.do", client.requests[2].url)
        assertEquals("https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1", client.requests[3].url)
        assertEquals(4, client.requests.size)
    }

    @Test
    fun campusCardSiteVerificationRetriesNcardLandingAfterCasRedirect() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=ncard",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://ncard.xjtu.edu.cn/plat/?ticket=ST-1",
                bodyText = "<html>校园卡</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.CAMPUS_CARD,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=ncard",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val success = assertIs<EngineLoginResult.SiteSessionSuccess>(result)
        assertEquals(SiteKey.CAMPUS_CARD, success.site)
        assertEquals(emptyMap(), success.headers)
        assertEquals("https://ncard.xjtu.edu.cn/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5", client.requests[2].url)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun campusCardSiteVerificationDoesNotFollowGenericCasCallbackWhenNcardLandingStillReturnsLogin() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/mfa/detect",
                bodyText = """{"code":0,"data":{"state":"mfa-state","need":false}}""",
            ),
            HttpResponse(
                code = 302,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=ncard",
                headers = mapOf(
                    "Location" to "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1",
                    "Set-Cookie" to "TGC=opaque; Path=/cas; Secure; HttpOnly",
                ),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )
        val engine = casEngine(client)

        val result = engine.beginSiteVerification(
            credentials = Credentials("3124000000", "secret"),
            accessMode = AccessMode.NORMAL,
            site = SiteKey.CAMPUS_CARD,
            context = SiteVerificationContext(
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=ncard",
                bodyText = loginPage(mfaEnabled = true),
            ),
        )

        val failure = assertIs<EngineLoginResult.ServiceChanged>(result)
        assertEquals("CAS 仍返回登录表单，未完成认证", failure.message)
        assertEquals("https://ncard.xjtu.edu.cn/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5", client.requests[2].url)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun safetyVerifyTwoFactorFailureDoesNotInvalidatePasswordAndCanRetry() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwxt",
                bodyText = safetyPage(),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/sec/initByType/securephone",
                bodyText = """{"code":0,"data":{"gid":"gid-sec","securePhone":"199****0000"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
                bodyText = """{"code":0}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                bodyText = """{"code":0,"data":{"status":"2"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwxt",
                bodyText = """<el-alert title="双因子验证失败"></el-alert>""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                bodyText = """{"code":0,"data":{"status":"2"}}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = casEngine(client)

        val first = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)
        val rejected = engine.submitMfa("000000")
        val recovered = engine.submitMfa("654321")

        assertIs<EngineLoginResult.NeedMfa>(first)
        val failure = assertIs<EngineLoginResult.VerificationRejected>(rejected)
        assertEquals("双因子验证失败", failure.message)
        assertIs<EngineLoginResult.Success>(recovered)
    }

    @Test
    fun wrongPasswordIsMappedToInvalidPassword() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = loginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 401,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = "<html>wrong</html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.login(Credentials("3124000000", "wrong"), AccessMode.NORMAL)

        assertIs<EngineLoginResult.InvalidPassword>(result)
        assertEquals(2, client.requests.size)
    }

    @Test
    fun webVpnAccessModeUsesWebVpnHttpClientForWholeLoginFlow() = runTest {
        val directClient = QueueHttpClient()
        val webVpnClient = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://webvpn.xjtu.edu.cn/http/77726476706e69737468656265737421f9f843d2323b62597b1dc7/login",
                bodyText = loginPage(mfaEnabled = false),
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://jwxt.xjtu.edu.cn",
                bodyText = "<html>ok</html>",
            ),
        )
        val engine = CasAuthEngine(
            httpClient = directClient,
            passwordEncryptor = PasswordEncryptor { password, publicKey ->
                PasswordEncryptionResult.Success("__RSA__$password@$publicKey")
            },
            visitorIdProvider = StaticVisitorIdProvider("visitor-1"),
            webVpnHttpClient = webVpnClient,
        )

        val result = engine.login(Credentials("3124000000", "secret"), AccessMode.WEBVPN)

        assertIs<EngineLoginResult.Success>(result)
        assertEquals(0, directClient.requests.size)
        assertEquals(2, webVpnClient.requests.size)
    }

    @Test
    fun missingExecutionIsServiceChangedNotBlindRetrySuccess() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = "<html><form id='fm1'></form><script>mfaEnabled=false</script></html>",
            ),
        )
        val engine = casEngine(client)

        val result = engine.login(Credentials("3124000000", "secret"), AccessMode.NORMAL)

        assertIs<EngineLoginResult.ServiceChanged>(result)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun defaultBrowserAuthFailsClosedUntilTicketExchangeIsConfigured() = runTest {
        val client = QueueHttpClient()
        val engine = casEngine(client)

        val start = engine.beginBrowserAuth(SiteKey.JWXT)
        val resume = engine.resumeBrowserAuth(
            BrowserAuthCallback(
                rawUrl = "xjtutoolbox://auth?state=state-1&ticket=ST-1",
                state = "state-1",
                ticket = "ST-1",
                code = null,
                site = SiteKey.JWXT,
            ),
        )

        assertIs<EngineLoginResult.ServiceChanged>(start)
        assertIs<EngineLoginResult.ServiceChanged>(resume)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun configuredBrowserAuthBuildsOfficialLoginUrlAndExchangesTicket() = runTest {
        var exchangedCallback: BrowserAuthCallback? = null
        val handler = CasBrowserAuthHandler(
            config = OfficialBrowserAuthConfig(
                callbackUrl = "xjtutoolbox://auth",
                stateProvider = BrowserAuthStateProvider { "state-1" },
            ),
            artifactExchanger = BrowserAuthArtifactExchanger { callback ->
                exchangedCallback = callback
                BrowserAuthExchangeResult.Success("3124000000")
            },
        )
        val engine = CasAuthEngine(
            httpClient = QueueHttpClient(),
            passwordEncryptor = PasswordEncryptor { password, publicKey ->
                PasswordEncryptionResult.Success("__RSA__$password@$publicKey")
            },
            visitorIdProvider = StaticVisitorIdProvider("visitor-1"),
            browserAuthHandler = handler,
        )

        val start = engine.beginBrowserAuth(SiteKey.JWXT)
        val challenge = assertIs<EngineLoginResult.NeedBrowserAuth>(start).challenge
        val resume = engine.resumeBrowserAuth(
            BrowserAuthCallback(
                rawUrl = "xjtutoolbox://auth?state=state-1&ticket=ST-1",
                state = "state-1",
                ticket = "ST-1",
                code = null,
                site = SiteKey.JWXT,
            ),
        )

        assertEquals("xjtutoolbox", challenge.callbackScheme)
        assertEquals("state-1", challenge.state)
        assertEquals(SiteKey.JWXT, challenge.site)
        assertTrue(challenge.loginUrl.startsWith("https://login.xjtu.edu.cn/cas/login?service="))
        assertTrue(challenge.loginUrl.contains("xjtutoolbox%3A%2F%2Fauth%3Fstate%3Dstate-1"))
        assertIs<EngineLoginResult.Success>(resume)
        assertEquals("ST-1", exchangedCallback?.ticket)
    }

    @Test
    fun serviceTicketExchangerValidatesTicketAgainstCasAndReturnsUser() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/serviceValidate",
                bodyText = """
                    <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                      <cas:authenticationSuccess>
                        <cas:user>3124000000</cas:user>
                      </cas:authenticationSuccess>
                    </cas:serviceResponse>
                """.trimIndent(),
            ),
        )
        val exchanger = CasServiceTicketExchanger(
            httpClient = client,
            config = OfficialBrowserAuthConfig(callbackUrl = "xjtutoolbox://auth"),
            serviceValidateUrl = "https://login.xjtu.edu.cn/cas/serviceValidate",
        )

        val result = exchanger.exchange(
            BrowserAuthCallback(
                rawUrl = "xjtutoolbox://auth?state=state-1&ticket=ST-1",
                state = "state-1",
                ticket = "ST-1",
                code = null,
                site = SiteKey.JWXT,
            ),
        )

        assertEquals(BrowserAuthExchangeResult.Success("3124000000"), result)
        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().url.startsWith("https://login.xjtu.edu.cn/cas/serviceValidate?"))
        assertTrue(client.requests.single().url.contains("service=xjtutoolbox%3A%2F%2Fauth%3Fstate%3Dstate-1"))
        assertTrue(client.requests.single().url.contains("ticket=ST-1"))
    }

    @Test
    fun serviceTicketExchangerReportsCasAuthenticationFailure() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/serviceValidate",
                bodyText = """
                    <cas:serviceResponse xmlns:cas="http://www.yale.edu/tp/cas">
                      <cas:authenticationFailure code="INVALID_TICKET">ticket 不存在</cas:authenticationFailure>
                    </cas:serviceResponse>
                """.trimIndent(),
            ),
        )
        val exchanger = CasServiceTicketExchanger(httpClient = client)

        val result = exchanger.exchange(
            BrowserAuthCallback(
                rawUrl = "xjtutoolbox://auth?state=state-1&ticket=ST-1",
                state = "state-1",
                ticket = "ST-1",
                code = null,
                site = null,
            ),
        )

        assertEquals(BrowserAuthExchangeResult.VerificationRejected("ticket 不存在"), result)
    }

    private fun casEngine(client: QueueHttpClient): CasAuthEngine =
        CasAuthEngine(
            httpClient = client,
            passwordEncryptor = PasswordEncryptor { password, publicKey ->
                PasswordEncryptionResult.Success("__RSA__$password@$publicKey")
            },
            visitorIdProvider = StaticVisitorIdProvider("visitor-1"),
        )

    private fun loginPage(mfaEnabled: Boolean): String = """
        <html>
          <form id="fm1">
            <input name="execution" value="e1s1" />
            <input name="pwdDefaultEncryptSalt" value="rsa-key" />
            <input name="failN" value="0" />
          </form>
          <script>window.globalConfig = {"mfaEnabled": $mfaEnabled}</script>
        </html>
    """.trimIndent()

    private fun captchaLoginPage(mfaEnabled: Boolean): String = """
        <html>
          <form id="fm1">
            <input name="execution" value="e1s1" />
            <input name="pwdDefaultEncryptSalt" value="rsa-key" />
            <input name="failN" value="3" />
            <img id="captcha" src="/cas/captcha.jpg" />
          </form>
          <script>window.globalConfig = {"mfaEnabled": $mfaEnabled}</script>
        </html>
    """.trimIndent()

    private fun safetyPage(): String = """
        <html>
          <title>Safety Verify</title>
          <form id="fm1">
            <input name="secState" value="sec-1" />
            <input name="execution" value="exec-sec" />
            <input name="_eventId" value="submit" />
            <input name="submit" value="Login1" />
          </form>
          <a href="/cas/sec/initByType">选择安全认证</a>
        </html>
    """.trimIndent()

    private fun accountChoicePage(): String = """
        <html>
          <form id="fm1">
            <input name="execution" value="choice-exec" />
            <div class="account-wrap">
              <div class="name">本科生账号</div>
              <el-radio class="checkbox-radio" label="undergraduate-label"></el-radio>
            </div>
            <div class="account-wrap">
              <div class="name">研究生账号</div>
              <el-radio class="checkbox-radio" label="postgraduate-label"></el-radio>
            </div>
          </form>
        </html>
    """.trimIndent()
}

private class QueueHttpClient(
    vararg responses: HttpResponse,
) : HttpClient {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirstOrNull()
            ?: error("No response queued for ${request.method} ${request.url}")
    }
}

private fun HttpRequest.bodyString(): String =
    body?.decodeToString() ?: ""
