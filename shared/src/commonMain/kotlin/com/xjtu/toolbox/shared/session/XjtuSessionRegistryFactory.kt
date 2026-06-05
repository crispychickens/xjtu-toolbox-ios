package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.SessionRegistry
import com.xjtu.toolbox.shared.auth.SiteKey

object XjtuSessionRegistryFactory {
    fun firstReleaseSessionRegistry(
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend = directBackend,
    ): SessionRegistry =
        SessionRegistry(
            mapOf(
                SiteKey.SCHEDULE to { mode ->
                    backendSiteSession(SiteKey.SCHEDULE, mode, directBackend, webVpnBackend)
                },
                SiteKey.GRADE to { mode ->
                    mobileJwappSession(mode, directBackend, webVpnBackend)
                },
                SiteKey.CAMPUS_CARD to { mode ->
                    campusCardSession(mode, directBackend, webVpnBackend)
                },
            ),
        )

    fun campusCardSessionRegistry(
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend = directBackend,
    ): SessionRegistry =
        SessionRegistry(
            mapOf(
                SiteKey.CAMPUS_CARD to { mode ->
                    campusCardSession(mode, directBackend, webVpnBackend)
                },
            ),
        )

    fun campusCardSession(
        mode: AccessMode,
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend = directBackend,
    ): BackendSiteSession {
        val backend = when (mode) {
            AccessMode.WEBVPN -> webVpnBackend
            AccessMode.AUTO,
            AccessMode.NORMAL,
            -> directBackend
        }
        val authenticator = NcardSessionAuthenticator(backend.httpClient)
        return BackendSiteSession(
            site = SiteKey.CAMPUS_CARD,
            backend = backend,
            authenticate = authenticator::authenticate,
        )
    }

    private fun backendSiteSession(
        site: SiteKey,
        mode: AccessMode,
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend,
    ): BackendSiteSession {
        val backend = backendFor(mode, directBackend, webVpnBackend)
        val authenticator = JwappSessionAuthenticator(site, backend.httpClient)
        return BackendSiteSession(
            site = site,
            backend = backend,
            authenticate = authenticator::authenticate,
        )
    }

    private fun mobileJwappSession(
        mode: AccessMode,
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend,
    ): BackendSiteSession {
        val backend = backendFor(mode, directBackend, webVpnBackend)
        val authenticator = MobileJwappSessionAuthenticator(backend.httpClient)
        return BackendSiteSession(
            site = SiteKey.GRADE,
            backend = backend,
            authenticate = authenticator::authenticate,
        )
    }

    private fun backendFor(
        mode: AccessMode,
        directBackend: SessionBackend,
        webVpnBackend: SessionBackend,
    ): SessionBackend =
        when (mode) {
            AccessMode.WEBVPN -> webVpnBackend
            AccessMode.AUTO,
            AccessMode.NORMAL,
            -> directBackend
        }
}
