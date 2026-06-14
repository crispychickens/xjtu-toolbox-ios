import Foundation

#if canImport(XJTUToolboxShared)
import XJTUToolboxShared
#endif

struct AppDependencies {
    let authManager: SharedAuthManaging
    let featureProvider: SharedFeatureProviding
}

enum AppDependencyFactory {
    static func makeDefault() -> AppDependencies {
        #if canImport(XJTUToolboxShared)
        switch XjtuLaunchArguments.dependencyMode {
        case .preview:
            makeKmpPreviewWithPlatformAdapters()
        case .realLoginValidation:
            makeKmpWithRealLoginValidation()
        case .realEmptyRooms:
            makeKmpPreviewWithRealEmptyRooms()
        case .realPublicData:
            makeKmpPreviewWithRealPublicData()
        case .realCampusCardAndPublicData:
            makeKmpWithRealCampusCardAndPublicData()
        case .realFirstReleaseCore:
            makeKmpWithRealFirstReleaseCore()
        }
        #else
        makePreview()
        #endif
    }

    static func makePreview() -> AppDependencies {
        return AppDependencies(
            authManager: PreviewAuthManager(),
            featureProvider: CachedFeatureProvider(
                provider: PreviewFeatureProvider(),
                cacheStore: UserDefaultsFeatureCacheStore(
                    keyPrefix: "com.xjtu.toolbox.ios.featureCache.debug.swiftPreview."
                )
            )
        )
    }

    #if canImport(XJTUToolboxShared)
    static func makeKmpPreviewWithPlatformAdapters() -> AppDependencies {
        let authManager = makeDefaultKmpAuthManager(engine: KmpPreviewAuthEngine())
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureService(authManager: authManager)
        )
    }

    static func makeKmpPreviewWithRealEmptyRooms() -> AppDependencies {
        let authManager = makeDefaultKmpAuthManager(engine: KmpPreviewAuthEngine())
        let emptyRoomRepository = EmptyRoomCdnRepository(
            httpClient: KmpURLSessionHttpClient(),
            baseUrl: "https://gh-release.xjtutoolbox.com/"
        )
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureServiceWithEmptyRooms(
                authManager: authManager,
                emptyRoomRepository: emptyRoomRepository
            )
        )
    }

    static func makeKmpPreviewWithRealPublicData() -> AppDependencies {
        let authManager = makeDefaultKmpAuthManager(engine: KmpPreviewAuthEngine())
        let httpClient = KmpURLSessionHttpClient()
        let noticeRepository = XjtuNoticeRepository(
            httpClient: httpClient,
            sources: XjtuNoticeSources.shared.firstReleaseDefaults,
            maxItemsPerSource: 20,
            pageSize: 20
        )
        let emptyRoomRepository = EmptyRoomCdnRepository(
            httpClient: httpClient,
            baseUrl: "https://gh-release.xjtutoolbox.com/"
        )
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureServiceWithPublicRepositories(
                authManager: authManager,
                noticeRepository: noticeRepository,
                emptyRoomRepository: emptyRoomRepository
            )
        )
    }

    static func makeKmpWithRealLoginValidation() -> AppDependencies {
        let authManager = makeKmpCasAuthManager()
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureService(authManager: authManager)
        )
    }

    static func makeKmpWithRealCampusCardAndPublicData() -> AppDependencies {
        let directBackend = KmpSessionBackendFactory.normal(minimumRequestIntervalMillis: 750)
        let webVpnBackend = KmpSessionBackendFactory.webvpn(minimumRequestIntervalMillis: 1_000)
        let registry = XjtuSessionRegistryFactory.shared.campusCardSessionRegistry(
            directBackend: directBackend,
            webVpnBackend: webVpnBackend
        )
        let authManager = makeKmpCasAuthManager(
            directBackend: directBackend,
            webVpnBackend: webVpnBackend,
            registry: registry
        )
        let publicHttpClient = KmpURLSessionHttpClient()
        let noticeRepository = XjtuNoticeRepository(
            httpClient: publicHttpClient,
            sources: XjtuNoticeSources.shared.firstReleaseDefaults,
            maxItemsPerSource: 20,
            pageSize: 20
        )
        let emptyRoomRepository = EmptyRoomCdnRepository(
            httpClient: publicHttpClient,
            baseUrl: "https://gh-release.xjtutoolbox.com/"
        )
        let campusCardRepository = NcardCampusCardRepository(
            baseUrl: "https://ncard.xjtu.edu.cn",
            timeFrom: nil,
            timeTo: nil
        )
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureServiceWithCampusCard(
                authManager: authManager,
                campusCardRepository: campusCardRepository,
                noticeRepository: noticeRepository,
                emptyRoomRepository: emptyRoomRepository
            )
        )
    }

    static func makeKmpWithRealFirstReleaseCore() -> AppDependencies {
        let directBackend = KmpSessionBackendFactory.normal(minimumRequestIntervalMillis: 750)
        let webVpnBackend = KmpSessionBackendFactory.webvpn(minimumRequestIntervalMillis: 1_000)
        let registry = XjtuSessionRegistryFactory.shared.firstReleaseSessionRegistry(
            directBackend: directBackend,
            webVpnBackend: webVpnBackend
        )
        let authManager = makeKmpCasAuthManager(
            directBackend: directBackend,
            webVpnBackend: webVpnBackend,
            registry: registry
        )
        let publicHttpClient = KmpURLSessionHttpClient()
        let scheduleRepository = JwappScheduleRepository(
            baseUrl: "https://jwxt.xjtu.edu.cn",
            maxTextbookPages: 5
        )
        let gradeRepository = MobileJwappGradeRepository(
            baseUrl: "https://jwapp.xjtu.edu.cn"
        )
        let campusCardRepository = NcardCampusCardRepository(
            baseUrl: "https://ncard.xjtu.edu.cn",
            timeFrom: nil,
            timeTo: nil
        )
        let noticeRepository = XjtuNoticeRepository(
            httpClient: publicHttpClient,
            sources: XjtuNoticeSources.shared.firstReleaseDefaults,
            maxItemsPerSource: 20,
            pageSize: 20
        )
        let emptyRoomRepository = EmptyRoomCdnRepository(
            httpClient: publicHttpClient,
            baseUrl: "https://gh-release.xjtutoolbox.com/"
        )
        let librarySeatRepository = XjtuLibrarySeatRepository(
            baseUrl: "http://rg.lib.xjtu.edu.cn:8086"
        )
        let couponRepository = XjtuCouponRepository(
            baseUrl: "https://egc.xjtu.edu.cn"
        )
        let schoolCourseRepository = XjtuSchoolCourseRepository(
            baseUrl: "https://jwxt.xjtu.edu.cn"
        )
        return makeKmp(
            authManager: authManager,
            featureService: XjtuToolboxPreviewFactory.shared.coreFeatureServiceWithRepositories(
                authManager: authManager,
                scheduleRepository: scheduleRepository,
                gradeRepository: gradeRepository,
                campusCardRepository: campusCardRepository,
                noticeRepository: noticeRepository,
                emptyRoomRepository: emptyRoomRepository,
                librarySeatRepository: librarySeatRepository,
                couponRepository: couponRepository,
                schoolCourseRepository: schoolCourseRepository
            )
        )
    }

    private static func makeDefaultKmpAuthManager(
        engine: AuthEngine,
        registry: SessionRegistry = SessionRegistry(factories: [:])
    ) -> AuthManager {
        let accessModeStore = KmpUserDefaultsAccessModeStore()
        let passwordAttemptStore = KmpUserDefaultsLoginAttemptStore(
            store: UserDefaultsLoginAttemptStore(keyPrefix: "com.xjtu.toolbox.ios.loginAttempt.password.")
        )
        let challengeAttemptStore = KmpUserDefaultsLoginAttemptStore(
            store: UserDefaultsLoginAttemptStore(keyPrefix: "com.xjtu.toolbox.ios.loginAttempt.challenge.")
        )
        return DefaultAuthManager(
            engine: engine,
            vault: KmpKeychainCredentialVault(),
            registry: registry,
            initialAccessMode: accessModeStore.load(),
            accessModeStore: accessModeStore,
            attemptGovernor: LoginAttemptGovernor(
                clock: SystemAttemptClock.shared,
                initialBackoffMillis: 2_000,
                maxBackoffMillis: 2_000,
                store: passwordAttemptStore
            ),
            challengeAttemptGovernor: LoginAttemptGovernor(
                clock: SystemAttemptClock.shared,
                initialBackoffMillis: 2_000,
                maxBackoffMillis: 2_000,
                store: challengeAttemptStore
            ),
            browserAuthCallbackParser: BrowserAuthCallbackParser()
        )
    }

    static func makeKmpCasAuthManager(
        directBackend: SessionBackend = KmpSessionBackendFactory.normal(minimumRequestIntervalMillis: 750),
        webVpnBackend: SessionBackend = KmpSessionBackendFactory.webvpn(minimumRequestIntervalMillis: 1_000),
        registry: SessionRegistry = SessionRegistry(factories: [:]),
        browserAuthHandler: BrowserAuthHandler? = nil
    ) -> AuthManager {
        let engine = CasAuthEngine(
            httpClient: directBackend.httpClient,
            passwordEncryptor: KmpRsaPasswordEncryptor(),
            visitorIdProvider: KmpUserDefaultsVisitorIdProvider(),
            config: CasAuthConfig(
                loginUrl: "https://login.xjtu.edu.cn/cas/login",
                captchaUrl: "https://login.xjtu.edu.cn/cas/captcha.jpg",
                publicKeyUrl: "https://login.xjtu.edu.cn/cas/jwt/publicKey",
                mfaDetectUrl: "https://login.xjtu.edu.cn/cas/mfa/detect",
                casBaseUrl: "https://login.xjtu.edu.cn/cas",
                attestSendUrl: "https://login.xjtu.edu.cn/attest/api/guard/securephone/send",
                attestValidUrl: "https://login.xjtu.edu.cn/attest/api/guard/securephone/valid",
                siteName: "CAS"
            ),
            webVpnHttpClient: webVpnBackend.httpClient,
            browserAuthHandler: browserAuthHandler
        )
        return makeDefaultKmpAuthManager(engine: engine, registry: registry)
    }

    static func makeKmp(
        authManager: AuthManager,
        featureService: CoreFeatureService
    ) -> AppDependencies {
        let cacheStore = InMemoryCoreFeatureCacheStore()
        let cachedFeatureService = CachedCoreFeatureService(
            delegate: featureService,
            cacheStore: cacheStore,
            clock: SystemFeatureCacheClock.shared,
            ttlMillis: 5 * 60_000
        )
        let kmpProvider = KmpFeatureProviderAdapter(
            service: cachedFeatureService,
            clearCache: { cacheStore.clear() }
        )
        let persistentProvider = CachedFeatureProvider(
            provider: kmpProvider,
            cacheStore: UserDefaultsFeatureCacheStore(
                keyPrefix: XjtuLaunchArguments.persistentFeatureCacheKeyPrefix
            ),
            shouldCache: { key in
                key == "schedule" ||
                    key.hasPrefix("notices:") ||
                    key.hasPrefix("emptyRooms:")
            }
        )
        return AppDependencies(
            authManager: KmpAuthManagerAdapter(authManager: authManager),
            featureProvider: persistentProvider
        )
    }
    #endif
}
