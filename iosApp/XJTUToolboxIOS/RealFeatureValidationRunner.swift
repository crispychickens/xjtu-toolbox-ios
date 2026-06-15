#if DEBUG
import Foundation

@MainActor
enum RealFeatureValidationRunner {
    private static let prefix = "[DEBUG-FEATURE-VALIDATION]"
    private static let resultsDefaultsKey = "debugRealFeatureValidationResults"
    private static let pendingFeatureDefaultsKey = "debugRealFeatureValidationPendingFeature"

    private enum Feature: String, CaseIterable {
        case librarySeats
        case coupons
        case schoolCourses
    }

    static func runIfRequested(authStore: AuthStore, featureStore: FeatureStore) async {
        guard XjtuLaunchArguments.shouldRunRealFeatureValidation else { return }
        UserDefaults.standard.removeObject(forKey: resultsDefaultsKey)
        clearPendingFeature()
        guard XjtuLaunchArguments.dependencyMode == .realFirstReleaseCore else {
            log("status=skipped reason=dependencyMode value=\(XjtuLaunchArguments.dependencyMode.cacheKeySegment)")
            return
        }

        let initialState = await authStore.syncStateFromManager()
        guard case .authenticated = initialState else { return }

        log("status=start dependencyMode=realFirstReleaseCore")
        await runFeatures(from: .librarySeats, authStore: authStore, featureStore: featureStore)
    }

    static func resumeIfPendingAfterSiteVerification(
        siteName: String,
        authStore: AuthStore,
        featureStore: FeatureStore
    ) async {
        guard XjtuLaunchArguments.shouldRunRealFeatureValidation else { return }
        guard XjtuLaunchArguments.dependencyMode == .realFirstReleaseCore else { return }
        guard let feature = pendingFeature() else { return }
        let state = await authStore.syncStateFromManager()
        guard case .authenticated = state else { return }
        log("status=resume afterSite=\(siteName) feature=\(feature.rawValue)")
        await runFeatures(from: feature, authStore: authStore, featureStore: featureStore)
    }

    static func recordAutoSiteVerification(
        siteName: String,
        phase: String,
        state: SharedAuthState,
        errorPresent: Bool
    ) {
        guard XjtuLaunchArguments.shouldRunRealFeatureValidation else { return }
        log(
            [
                "autoSiteVerification",
                "site=\(siteName)",
                "phase=\(phase)",
                "state=\(stateName(state))",
                "errorPresent=\(errorPresent)",
            ].joined(separator: " ")
        )
    }

    private static func runFeatures(
        from start: Feature,
        authStore: AuthStore,
        featureStore: FeatureStore
    ) async {
        guard let startIndex = Feature.allCases.firstIndex(of: start) else { return }
        for feature in Feature.allCases[startIndex...] {
            let didFinishFeature: Bool
            switch feature {
            case .librarySeats:
                didFinishFeature = await validateLibrarySeats(authStore: authStore, featureStore: featureStore)
            case .coupons:
                didFinishFeature = await validateCoupons(authStore: authStore, featureStore: featureStore)
            case .schoolCourses:
                didFinishFeature = await validateSchoolCourses(authStore: authStore, featureStore: featureStore)
            }
            guard didFinishFeature else { return }
        }
        clearPendingFeature()
        log("status=finished")
    }

    private static func validateLibrarySeats(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        await validate(
            name: "librarySeats",
            authStore: authStore,
            errorMessage: { featureStore.errorMessage(for: .librarySeats) },
            operation: { await featureStore.loadLibrarySeats(force: true) },
            summary: {
                guard let snapshot = featureStore.librarySeats else {
                    return "snapshotPresent=false"
                }
                let availableSeats = snapshot.seats.filter(\.available).count
                return [
                    "areas=\(snapshot.areas.count)",
                    "seats=\(snapshot.seats.count)",
                    "availableSeats=\(availableSeats)",
                    "recommendedAreas=\(snapshot.recommendedAreas.count)",
                    "bookingPresent=\(snapshot.myBooking != nil)",
                ].joined(separator: " ")
            }
        )
    }

    private static func validateCoupons(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        await validate(
            name: "coupons",
            authStore: authStore,
            errorMessage: { featureStore.errorMessage(for: .coupons) },
            operation: { await featureStore.loadCoupons(force: true) },
            summary: {
                [
                    "loaded=\(featureStore.coupons.count)",
                    "total=\(featureStore.totalCoupons)",
                    "filter=\(featureStore.couponFilter.rawValue)",
                    "canLoadMore=\(featureStore.canLoadMoreCoupons)",
                ].joined(separator: " ")
            }
        )
    }

    private static func validateSchoolCourses(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        await validate(
            name: "schoolCourses",
            authStore: authStore,
            errorMessage: { featureStore.errorMessage(for: .schoolCourses) },
            operation: { await featureStore.searchSchoolCourses() },
            summary: {
                [
                    "loaded=\(featureStore.schoolCourses.count)",
                    "total=\(featureStore.totalSchoolCourses)",
                    "termPresent=\(!featureStore.schoolCourseTermCode.isEmpty)",
                    "canLoadMore=\(featureStore.canLoadMoreSchoolCourses)",
                ].joined(separator: " ")
            }
        )
    }

    private static func validate(
        name: String,
        authStore: AuthStore,
        errorMessage: () -> String?,
        operation: () async -> Void,
        summary: () -> String
    ) async -> Bool {
        log("feature=\(name) status=start")
        await operation()
        let state = await authStore.syncStateFromManager()
        if case .siteVerificationRequired(_, let siteName, _) = state {
            setPendingFeature(name)
            log("feature=\(name) status=siteVerificationRequired site=\(siteName)")
            return false
        }
        clearPendingFeature(name)
        if errorMessage() != nil {
            log("feature=\(name) status=failed errorPresent=true")
            return true
        }
        log("feature=\(name) status=success \(summary())")
        return true
    }

    private static func stateName(_ state: SharedAuthState) -> String {
        switch state {
        case .anonymous:
            return "anonymous"
        case .authenticating:
            return "authenticating"
        case .awaitingCaptcha:
            return "awaitingCaptcha"
        case .awaitingMfa:
            return "awaitingMfa"
        case .awaitingAccountChoice:
            return "awaitingAccountChoice"
        case .awaitingBrowserAuth:
            return "awaitingBrowserAuth"
        case .authenticated:
            return "authenticated"
        case .siteVerificationRequired:
            return "siteVerificationRequired"
        case .passwordInvalidated:
            return "passwordInvalidated"
        }
    }

    private static func log(_ message: String) {
        let line = "\(prefix) \(message)"
        var results = UserDefaults.standard.stringArray(forKey: resultsDefaultsKey) ?? []
        results.append(line)
        UserDefaults.standard.set(results, forKey: resultsDefaultsKey)
        UserDefaults.standard.synchronize()
        print(line)
    }

    private static func pendingFeature() -> Feature? {
        guard let rawValue = UserDefaults.standard.string(forKey: pendingFeatureDefaultsKey) else {
            return nil
        }
        return Feature(rawValue: rawValue)
    }

    private static func setPendingFeature(_ name: String) {
        UserDefaults.standard.set(name, forKey: pendingFeatureDefaultsKey)
        UserDefaults.standard.synchronize()
    }

    private static func clearPendingFeature(_ name: String? = nil) {
        if let name,
           UserDefaults.standard.string(forKey: pendingFeatureDefaultsKey) != name {
            return
        }
        UserDefaults.standard.removeObject(forKey: pendingFeatureDefaultsKey)
        UserDefaults.standard.synchronize()
    }
}
#endif
