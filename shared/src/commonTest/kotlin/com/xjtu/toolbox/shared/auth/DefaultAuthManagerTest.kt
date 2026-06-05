package com.xjtu.toolbox.shared.auth

import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultAuthManagerTest {
    @Test
    fun loginSuccessPersistsCredentialsAndCanEnsureSession() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val vault = InMemoryCredentialVault()
        val manager = DefaultAuthManager(engine, vault, SessionRegistry(emptyMap()), AccessMode.NORMAL)

        val login = manager.login("3124000000", "secret")
        assertIs<LoginOutcome.Success>(login)

        val session = manager.ensureSession(SiteKey.JWXT)
        assertEquals(SiteKey.JWXT, session.site)
        assertTrue(session.isAuthenticated)
        assertIs<AuthState.Authenticated>(manager.authState.value)
    }

    @Test
    fun restoreSavedCredentialsRehydratesAuthStateWithoutCallingLogin() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val vault = InMemoryCredentialVault()
        vault.save(Credentials("3124000000", "secret"))
        val manager = DefaultAuthManager(engine, vault, SessionRegistry(emptyMap()), AccessMode.NORMAL)

        val restored = manager.restoreSavedCredentials()

        val authenticated = assertIs<AuthState.Authenticated>(restored)
        assertEquals("3124000000", authenticated.username)
        assertEquals(0, engine.loginCalls)
        assertIs<AuthState.Authenticated>(manager.authState.value)
    }

    @Test
    fun restoreSavedCredentialsDoesNotReplacePendingChallengeState() = runTest {
        val challenge = CaptchaChallenge(imageBase64 = "captcha-image", site = null)
        val engine = ScriptedAuthEngine(EngineLoginResult.NeedCaptcha(challenge))
        val vault = InMemoryCredentialVault()
        vault.save(Credentials("3124000000", "secret"))
        val manager = DefaultAuthManager(engine, vault, SessionRegistry(emptyMap()), AccessMode.NORMAL)

        manager.login("3124000000", "secret")
        val restored = manager.restoreSavedCredentials()

        assertIs<AuthState.AwaitingCaptcha>(restored)
        assertIs<AuthState.AwaitingCaptcha>(manager.authState.value)
    }

    @Test
    fun ensureSessionReusesAuthenticatedSiteSession() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val siteSession = CountingSiteSession(SiteKey.JWXT, AccessMode.AUTO)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.JWXT to { siteSession })),
        )

        manager.login("3124000000", "secret")
        manager.ensureSession(SiteKey.JWXT)
        manager.ensureSession(SiteKey.JWXT)

        assertEquals(1, siteSession.ensureCalls)
    }

    @Test
    fun siteVerificationFailureUpdatesAuthStateAndKeepsSessionUnauthenticated() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val siteSession = SiteVerificationFailingSession(SiteKey.SCHEDULE, AccessMode.NORMAL)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.SCHEDULE to { siteSession })),
            initialAccessMode = AccessMode.NORMAL,
        )

        manager.login("3124000000", "secret")
        val failure = assertFailsWith<SiteVerificationRequiredException> {
            manager.ensureSession(SiteKey.SCHEDULE)
        }

        assertEquals(SiteKey.SCHEDULE, failure.site)
        assertFalse(siteSession.isAuthenticated)
        assertEquals(1, siteSession.invalidateCalls)
        val state = assertIs<AuthState.SiteVerificationRequired>(manager.authState.value)
        assertEquals("3124000000", state.username)
        assertEquals(SiteKey.SCHEDULE, state.site)
        assertEquals("JWAPP 需要重新完成 CAS/Safety Verify 验证", state.message)
    }

    @Test
    fun siteVerificationCanBeStartedFromCapturedContextAfterUserAction() = runTest {
        val context = SiteVerificationContext(
            finalUrl = "https://login.xjtu.edu.cn/cas/sec/index",
            bodyText = """<input name="secState" value="sec-1"><input name="execution" value="e1s1">""",
        )
        val challenge = MfaChallenge(MfaFlow.SAFETY_VERIFY, "188****0000", SiteKey.SCHEDULE)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.Success("3124000000"),
            EngineLoginResult.NeedMfa(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val siteSession = SiteVerificationFailingSession(SiteKey.SCHEDULE, AccessMode.NORMAL, context)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.SCHEDULE to { siteSession })),
            initialAccessMode = AccessMode.NORMAL,
        )

        manager.login("3124000000", "secret")
        assertFailsWith<SiteVerificationRequiredException> {
            manager.ensureSession(SiteKey.SCHEDULE)
        }
        val started = manager.beginSiteVerification(SiteKey.SCHEDULE)
        val finished = manager.submitMfa("123456")

        val needMfa = assertIs<LoginOutcome.NeedMfa>(started)
        assertEquals(SiteKey.SCHEDULE, needMfa.challenge.site)
        assertIs<LoginOutcome.Success>(finished)
        assertEquals(1, engine.beginSiteVerificationCalls)
        assertEquals(context, engine.lastSiteVerificationContext)
        assertEquals(1, engine.submitMfaCalls)
    }

    @Test
    fun siteVerificationSuccessAdoptsReturnedSiteSessionHeaders() = runTest {
        val context = SiteVerificationContext(
            finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp",
            bodyText = "<html>CAS login</html>",
        )
        val headers = mapOf(
            "Authorization" to "token-1",
            "User-Agent" to "mobile-ua",
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.Success("3124000000"),
            EngineLoginResult.SiteSessionSuccess("3124000000", SiteKey.GRADE, headers),
        )
        val siteSession = HeaderAdoptingSiteSession(SiteKey.GRADE, AccessMode.NORMAL, context)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.GRADE to { siteSession })),
            initialAccessMode = AccessMode.NORMAL,
        )

        manager.login("3124000000", "secret")
        assertFailsWith<SiteVerificationRequiredException> {
            manager.ensureSession(SiteKey.GRADE)
        }
        val recovered = manager.beginSiteVerification(SiteKey.GRADE)
        val authenticated = assertIs<AuthState.Authenticated>(manager.authState.value)
        val session = manager.ensureSession(SiteKey.GRADE)

        assertIs<LoginOutcome.Success>(recovered)
        assertTrue(SiteKey.GRADE in authenticated.activeSites)
        assertEquals(headers, siteSession.adoptedHeaders)
        assertEquals(1, siteSession.ensureCalls)
        assertTrue(session.isAuthenticated)
        assertEquals(1, engine.beginSiteVerificationCalls)
    }

    @Test
    fun rejectedSiteVerificationStartBacksOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val context = SiteVerificationContext(
            finalUrl = "https://login.xjtu.edu.cn/cas/sec/index",
            bodyText = """<input name="secState" value="sec-1"><input name="execution" value="e1s1">""",
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.Success("3124000000"),
            EngineLoginResult.VerificationRejected("验证上下文已失效"),
            EngineLoginResult.Success("3124000000"),
        )
        val siteSession = SiteVerificationFailingSession(SiteKey.SCHEDULE, AccessMode.NORMAL, context)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.SCHEDULE to { siteSession })),
            initialAccessMode = AccessMode.NORMAL,
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.login("3124000000", "secret")
        assertFailsWith<SiteVerificationRequiredException> {
            manager.ensureSession(SiteKey.SCHEDULE)
        }
        val rejected = manager.beginSiteVerification(SiteKey.SCHEDULE)
        val blocked = manager.beginSiteVerification(SiteKey.SCHEDULE)

        assertIs<AuthFailure.VerificationRejected>(assertIs<LoginOutcome.Failure>(rejected).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.beginSiteVerificationCalls)

        clock.advance(2_000)
        val recovered = manager.beginSiteVerification(SiteKey.SCHEDULE)

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(2, engine.beginSiteVerificationCalls)
    }

    @Test
    fun transientSiteVerificationStartFailureKeepsSiteVerificationState() = runTest {
        val context = SiteVerificationContext(
            finalUrl = "https://login.xjtu.edu.cn/cas/sec/index",
            bodyText = """<input name="secState" value="sec-1"><input name="execution" value="e1s1">""",
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.Success("3124000000"),
            EngineLoginResult.ServiceChanged("CAS 仍返回登录表单，未完成认证"),
        )
        val siteSession = SiteVerificationFailingSession(SiteKey.SCHEDULE, AccessMode.NORMAL, context)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(mapOf(SiteKey.SCHEDULE to { siteSession })),
            initialAccessMode = AccessMode.NORMAL,
        )

        manager.login("3124000000", "secret")
        assertFailsWith<SiteVerificationRequiredException> {
            manager.ensureSession(SiteKey.SCHEDULE)
        }
        val failure = manager.beginSiteVerification(SiteKey.SCHEDULE)

        assertIs<AuthFailure.ServiceChanged>(assertIs<LoginOutcome.Failure>(failure).reason)
        val state = assertIs<AuthState.SiteVerificationRequired>(manager.authState.value)
        assertEquals("3124000000", state.username)
        assertEquals(SiteKey.SCHEDULE, state.site)
        assertEquals("CAS 仍返回登录表单，未完成认证", state.message)
    }

    @Test
    fun accessModeLoadsFromStoreAndInvalidatesExistingSessionsWhenChanged() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val store = InMemoryAccessModeStore(AccessMode.WEBVPN)
        val directSession = CountingSiteSession(SiteKey.JWXT, AccessMode.NORMAL)
        val webVpnSession = CountingSiteSession(SiteKey.JWXT, AccessMode.WEBVPN)
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(
                mapOf(
                    SiteKey.JWXT to { mode ->
                        if (mode == AccessMode.WEBVPN) webVpnSession else directSession
                    },
                ),
            ),
            accessModeStore = store,
        )

        assertEquals(AccessMode.WEBVPN, manager.currentAccessMode.value)

        manager.login("3124000000", "secret")
        manager.ensureSession(SiteKey.JWXT)
        manager.setAccessMode(AccessMode.NORMAL)
        manager.ensureSession(SiteKey.JWXT)

        assertEquals(AccessMode.NORMAL, store.load())
        assertEquals(1, webVpnSession.ensureCalls)
        assertEquals(1, webVpnSession.invalidateCalls)
        assertEquals(1, directSession.ensureCalls)
    }

    @Test
    fun mfaChallengeKeepsAuthModuleAsSingleStateMachineHost() = runTest {
        val challenge = MfaChallenge(MfaFlow.SAFETY_VERIFY, "188****0000", SiteKey.JWXT)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedMfa(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(engine, InMemoryCredentialVault(), SessionRegistry(emptyMap()))

        val first = manager.login("3124000000", "secret")
        assertIs<LoginOutcome.NeedMfa>(first)
        assertIs<AuthState.AwaitingMfa>(manager.authState.value)

        val second = manager.submitMfa("123456")
        assertIs<LoginOutcome.Success>(second)
        assertIs<AuthState.Authenticated>(manager.authState.value)
    }

    @Test
    fun captchaChallengeKeepsAuthModuleAsSingleStateMachineHost() = runTest {
        val challenge = CaptchaChallenge(imageBase64 = "captcha-image", site = null)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedCaptcha(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(engine, InMemoryCredentialVault(), SessionRegistry(emptyMap()))

        val first = manager.login("3124000000", "secret")
        assertIs<LoginOutcome.NeedCaptcha>(first)
        assertIs<AuthState.AwaitingCaptcha>(manager.authState.value)

        val second = manager.submitCaptcha("abcd")
        assertIs<LoginOutcome.Success>(second)
        assertIs<AuthState.Authenticated>(manager.authState.value)
    }

    @Test
    fun accountChoiceChallengeKeepsAuthModuleAsSingleStateMachineHost() = runTest {
        val challenge = AccountChoiceChallenge(
            choices = listOf(
                AccountChoice("undergraduate", "本科生账号", AccountType.UNDERGRADUATE),
                AccountChoice("postgraduate", "研究生账号", AccountType.POSTGRADUATE),
            ),
            site = null,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedAccountChoice(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(engine, InMemoryCredentialVault(), SessionRegistry(emptyMap()))

        val first = manager.login("3124000000", "secret")
        assertIs<LoginOutcome.NeedAccountChoice>(first)
        assertIs<AuthState.AwaitingAccountChoice>(manager.authState.value)

        val second = manager.submitAccountChoice("postgraduate")
        assertIs<LoginOutcome.Success>(second)
        assertIs<AuthState.Authenticated>(manager.authState.value)
        assertEquals("postgraduate", engine.lastAccountChoiceId)
    }

    @Test
    fun rejectedCaptchaSubmissionBacksOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = CaptchaChallenge(imageBase64 = "captcha-image", site = null)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedCaptcha(challenge),
            EngineLoginResult.VerificationRejected("验证码错误"),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.login("3124000000", "secret")
        val rejected = manager.submitCaptcha("bad")
        val blocked = manager.submitCaptcha("abcd")

        assertIs<AuthFailure.VerificationRejected>(assertIs<LoginOutcome.Failure>(rejected).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.submitCaptchaCalls)
        assertIs<AuthState.AwaitingCaptcha>(manager.authState.value)

        clock.advance(2_000)
        val recovered = manager.submitCaptcha("abcd")

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(2, engine.submitCaptchaCalls)
    }

    @Test
    fun rejectedMfaSubmissionBacksOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = MfaChallenge(MfaFlow.SAFETY_VERIFY, "188****0000", SiteKey.JWXT)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedMfa(challenge),
            EngineLoginResult.VerificationRejected("验证码错误"),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.login("3124000000", "secret")
        val rejected = manager.submitMfa("000000")
        val blocked = manager.submitMfa("111111")

        assertIs<AuthFailure.VerificationRejected>(assertIs<LoginOutcome.Failure>(rejected).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.submitMfaCalls)
        assertIs<AuthState.AwaitingMfa>(manager.authState.value)

        clock.advance(2_000)
        val recovered = manager.submitMfa("123456")

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(2, engine.submitMfaCalls)
    }

    @Test
    fun rejectedAccountChoiceBacksOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = AccountChoiceChallenge(
            choices = listOf(
                AccountChoice("undergraduate", "本科生账号", AccountType.UNDERGRADUATE),
                AccountChoice("postgraduate", "研究生账号", AccountType.POSTGRADUATE),
            ),
            site = null,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedAccountChoice(challenge),
            EngineLoginResult.VerificationRejected("账号类型无效"),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.login("3124000000", "secret")
        val rejected = manager.submitAccountChoice("bad")
        val blocked = manager.submitAccountChoice("undergraduate")

        assertIs<AuthFailure.VerificationRejected>(assertIs<LoginOutcome.Failure>(rejected).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.submitAccountChoiceCalls)
        assertIs<AuthState.AwaitingAccountChoice>(manager.authState.value)

        clock.advance(2_000)
        val recovered = manager.submitAccountChoice("undergraduate")

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(2, engine.submitAccountChoiceCalls)
    }

    @Test
    fun invalidPasswordClearsStateAndBlocksReuseUntilCredentialsChange() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.InvalidPassword("JWXT"))
        val vault = InMemoryCredentialVault()
        val manager = DefaultAuthManager(engine, vault, SessionRegistry(emptyMap()))

        val result = manager.login("3124000000", "wrong")
        val retry = manager.login("3124000000", "wrong")

        assertIs<LoginOutcome.Failure>(result)
        assertIs<LoginOutcome.Failure>(retry)
        assertIs<AuthState.PasswordInvalidated>(manager.authState.value)
        assertEquals(null, vault.load())
        assertEquals(1, engine.loginCalls)
    }

    @Test
    fun transientLoginFailuresBackOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NetworkError("timeout"),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            attemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        val first = manager.login("3124000000", "secret")
        val blocked = manager.login("3124000000", "secret")

        assertIs<LoginOutcome.Failure>(first)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.loginCalls)

        clock.advance(2_000)
        val recovered = manager.login("3124000000", "secret")

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(2, engine.loginCalls)
    }

    @Test
    fun transientLoginBackOffCanSurviveManagerRecreation() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val store = InMemoryLoginAttemptStore()
        val firstEngine = ScriptedAuthEngine(EngineLoginResult.NetworkError("timeout"))
        val firstManager = DefaultAuthManager(
            engine = firstEngine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            attemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
                store = store,
            ),
        )

        firstManager.login("3124000000", "secret")

        val secondEngine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val recreatedManager = DefaultAuthManager(
            engine = secondEngine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            attemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
                store = store,
            ),
        )

        val blocked = recreatedManager.login("3124000000", "secret")

        assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(0, secondEngine.loginCalls)

        clock.advance(2_000)
        val recovered = recreatedManager.login("3124000000", "secret")

        assertIs<LoginOutcome.Success>(recovered)
        assertEquals(1, secondEngine.loginCalls)
    }

    @Test
    fun browserAuthUsesOfficialHandoffAndCallbackWithoutPasswordCredentials() = runTest {
        val challenge = BrowserAuthChallenge(
            loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
            callbackScheme = "xjtutoolbox",
            state = "state-1",
            site = SiteKey.JWXT,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedBrowserAuth(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(engine, InMemoryCredentialVault(), SessionRegistry(emptyMap()))

        val start = manager.beginBrowserAuth(SiteKey.JWXT)
        val passwordAttemptWhileBrowserIsPending = manager.login("3124000000", "secret")
        val pendingState = manager.authState.value
        val finished = manager.resumeBrowserAuth("xjtutoolbox://auth?state=state-1&ticket=ST-1")
        val session = manager.ensureSession(SiteKey.JWXT)

        assertIs<LoginOutcome.NeedBrowserAuth>(start)
        assertIs<LoginOutcome.NeedBrowserAuth>(passwordAttemptWhileBrowserIsPending)
        assertIs<AuthState.AwaitingBrowserAuth>(pendingState)
        assertIs<LoginOutcome.Success>(finished)
        assertTrue(session.isAuthenticated)
        assertEquals(0, engine.loginCalls)
        assertEquals(1, engine.beginBrowserAuthCalls)
        assertEquals(1, engine.resumeBrowserAuthCalls)
        assertEquals("ST-1", engine.lastBrowserAuthCallback?.ticket)
    }

    @Test
    fun browserAuthStartTransientFailuresBackOffWithoutCallingSchoolSystemsAgain() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = BrowserAuthChallenge(
            loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
            callbackScheme = "xjtutoolbox",
            state = "state-1",
            site = SiteKey.JWXT,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NetworkError("timeout"),
            EngineLoginResult.NeedBrowserAuth(challenge),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        val failed = manager.beginBrowserAuth(SiteKey.JWXT)
        val blocked = manager.beginBrowserAuth(SiteKey.JWXT)

        assertIs<AuthFailure.Network>(assertIs<LoginOutcome.Failure>(failed).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.beginBrowserAuthCalls)

        clock.advance(2_000)
        val recovered = manager.beginBrowserAuth(SiteKey.JWXT)

        assertIs<LoginOutcome.NeedBrowserAuth>(recovered)
        assertEquals(2, engine.beginBrowserAuthCalls)
    }

    @Test
    fun browserCallbackIsIgnoredUnlessBrowserAuthIsPending() = runTest {
        val engine = ScriptedAuthEngine(EngineLoginResult.Success("3124000000"))
        val manager = DefaultAuthManager(engine, InMemoryCredentialVault(), SessionRegistry(emptyMap()))

        val result = manager.resumeBrowserAuth("xjtutoolbox://auth?ticket=ST-1")

        assertIs<LoginOutcome.Failure>(result)
        assertEquals(0, engine.resumeBrowserAuthCalls)
    }

    @Test
    fun browserCallbackStateMismatchDoesNotCallEngine() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = BrowserAuthChallenge(
            loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
            callbackScheme = "xjtutoolbox",
            state = "expected-state",
            site = SiteKey.JWXT,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedBrowserAuth(challenge),
            EngineLoginResult.Success("3124000000"),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.beginBrowserAuth(SiteKey.JWXT)
        val result = manager.resumeBrowserAuth("xjtutoolbox://auth?state=wrong-state&ticket=ST-1")
        val blocked = manager.resumeBrowserAuth("xjtutoolbox://auth?state=expected-state&ticket=ST-1")

        val failure = assertIs<LoginOutcome.Failure>(result)
        assertIs<AuthFailure.BrowserAuthRejected>(failure.reason)
        assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blocked).reason)
        assertIs<AuthState.AwaitingBrowserAuth>(manager.authState.value)
        assertEquals(0, engine.resumeBrowserAuthCalls)
    }

    @Test
    fun browserCallbackTransientFailuresBackOffNewHandoffAttempts() = runTest {
        val clock = ManualAttemptClock(now = 1_000)
        val challenge = BrowserAuthChallenge(
            loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
            callbackScheme = "xjtutoolbox",
            state = "state-1",
            site = SiteKey.JWXT,
        )
        val engine = ScriptedAuthEngine(
            EngineLoginResult.NeedBrowserAuth(challenge),
            EngineLoginResult.NetworkError("ticket exchange timeout"),
            EngineLoginResult.NeedBrowserAuth(challenge.copy(state = "state-2")),
        )
        val manager = DefaultAuthManager(
            engine = engine,
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            challengeAttemptGovernor = LoginAttemptGovernor(
                clock = clock,
                initialBackoffMillis = 2_000,
            ),
        )

        manager.beginBrowserAuth(SiteKey.JWXT)
        val failed = manager.resumeBrowserAuth("xjtutoolbox://auth?state=state-1&ticket=ST-1")
        val blockedStart = manager.beginBrowserAuth(SiteKey.JWXT)

        assertIs<AuthFailure.Network>(assertIs<LoginOutcome.Failure>(failed).reason)
        val rateLimited = assertIs<AuthFailure.RateLimited>(assertIs<LoginOutcome.Failure>(blockedStart).reason)
        assertEquals(2_000, rateLimited.retryAfterMillis)
        assertEquals(1, engine.beginBrowserAuthCalls)
        assertEquals(1, engine.resumeBrowserAuthCalls)

        clock.advance(2_000)
        val recoveredStart = manager.beginBrowserAuth(SiteKey.JWXT)

        val recoveredChallenge = assertIs<LoginOutcome.NeedBrowserAuth>(recoveredStart).challenge
        assertEquals("state-2", recoveredChallenge.state)
        assertEquals(2, engine.beginBrowserAuthCalls)
    }
}

private class ScriptedAuthEngine(
    private vararg val results: EngineLoginResult,
) : AuthEngine {
    private var index = 0
    var loginCalls: Int = 0
        private set
    var beginBrowserAuthCalls: Int = 0
        private set
    var beginSiteVerificationCalls: Int = 0
        private set
    var submitMfaCalls: Int = 0
        private set
    var submitCaptchaCalls: Int = 0
        private set
    var submitAccountChoiceCalls: Int = 0
        private set
    var resumeBrowserAuthCalls: Int = 0
        private set

    override suspend fun login(credentials: Credentials, accessMode: AccessMode): EngineLoginResult {
        loginCalls += 1
        return results[index++]
    }

    override suspend fun beginBrowserAuth(site: SiteKey?): EngineLoginResult {
        beginBrowserAuthCalls += 1
        return results[index++]
    }

    var lastSiteVerificationContext: SiteVerificationContext? = null
        private set

    override suspend fun beginSiteVerification(
        credentials: Credentials,
        accessMode: AccessMode,
        site: SiteKey,
        context: SiteVerificationContext,
    ): EngineLoginResult {
        beginSiteVerificationCalls += 1
        lastSiteVerificationContext = context
        return results[index++]
    }

    override suspend fun submitCaptcha(code: String): EngineLoginResult {
        submitCaptchaCalls += 1
        return results[index++]
    }

    override suspend fun submitMfa(code: String): EngineLoginResult {
        submitMfaCalls += 1
        return results[index++]
    }

    var lastAccountChoiceId: String? = null
        private set

    override suspend fun submitAccountChoice(choiceId: String): EngineLoginResult {
        submitAccountChoiceCalls += 1
        lastAccountChoiceId = choiceId
        return results[index++]
    }

    var lastBrowserAuthCallback: BrowserAuthCallback? = null
        private set

    override suspend fun resumeBrowserAuth(callback: BrowserAuthCallback): EngineLoginResult {
        resumeBrowserAuthCalls += 1
        lastBrowserAuthCallback = callback
        return results[index++]
    }

    override suspend fun logout() = Unit
}

private class ManualAttemptClock(now: Long = 0) : AttemptClock {
    private var current = now

    override fun nowMillis(): Long = current

    fun advance(millis: Long) {
        current += millis
    }
}

private class CountingSiteSession(
    override val site: SiteKey,
    override val accessMode: AccessMode,
) : SiteSession {
    var ensureCalls: Int = 0
        private set
    var invalidateCalls: Int = 0
        private set

    override var isAuthenticated: Boolean = false
        private set

    override suspend fun ensureAuthenticated(context: AuthContext): SiteSession {
        ensureCalls += 1
        isAuthenticated = true
        return this
    }

    override suspend fun invalidate() {
        invalidateCalls += 1
        isAuthenticated = false
    }
}

private class HeaderAdoptingSiteSession(
    override val site: SiteKey,
    override val accessMode: AccessMode,
    private val verificationContext: SiteVerificationContext,
) : HeaderAuthenticatedSiteSession {
    var ensureCalls: Int = 0
        private set
    var adoptedHeaders: Map<String, String> = emptyMap()
        private set

    override var isAuthenticated: Boolean = false
        private set
    override var username: String? = null
        private set

    override suspend fun ensureAuthenticated(context: AuthContext): SiteSession {
        ensureCalls += 1
        throw SiteVerificationRequiredException(
            site = site,
            message = "移动教务需要重新完成 CAS/Safety Verify 验证",
            verificationContext = verificationContext,
        )
    }

    override suspend fun adoptAuthenticatedHeaders(
        context: AuthContext,
        headers: Map<String, String>,
    ): SiteSession {
        username = context.username
        adoptedHeaders = headers
        isAuthenticated = true
        return this
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        check(isAuthenticated) { "session $site is not authenticated" }
        return HttpResponse(code = 200, finalUrl = request.url)
    }

    override suspend fun invalidate() {
        username = null
        adoptedHeaders = emptyMap()
        isAuthenticated = false
    }
}

private class SiteVerificationFailingSession(
    override val site: SiteKey,
    override val accessMode: AccessMode,
    private val verificationContext: SiteVerificationContext? = null,
) : SiteSession {
    var invalidateCalls: Int = 0
        private set

    override var isAuthenticated: Boolean = false
        private set

    override suspend fun ensureAuthenticated(context: AuthContext): SiteSession {
        throw SiteVerificationRequiredException(
            site = site,
            message = "JWAPP 需要重新完成 CAS/Safety Verify 验证",
            verificationContext = verificationContext,
        )
    }

    override suspend fun invalidate() {
        invalidateCalls += 1
        isAuthenticated = false
    }
}
