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

    private struct SchoolCourseProbe {
        let label: String
        let nameQuery: String
        let teacherQuery: String
        let campusCode: String
        let weekday: Int
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
        let didFinish = await validate(
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
        guard didFinish, featureStore.errorMessage(for: .coupons) == nil else { return didFinish }
        return await validateCouponFilterPages(authStore: authStore, featureStore: featureStore)
    }

    private static func validateSchoolCourses(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        let didFinish = await validate(
            name: "schoolCourses",
            authStore: authStore,
            errorMessage: { featureStore.errorMessage(for: .schoolCourses) },
            operation: { await featureStore.searchSchoolCourses() },
            summary: {
                [
                    "loaded=\(featureStore.schoolCourses.count)",
                    "total=\(featureStore.totalSchoolCourses)",
                    "page=\(featureStore.schoolCoursePage)",
                    "termPresent=\(!featureStore.schoolCourseTermCode.isEmpty)",
                    "canLoadMore=\(featureStore.canLoadMoreSchoolCourses)",
                ].joined(separator: " ")
            }
        )
        guard didFinish, featureStore.errorMessage(for: .schoolCourses) == nil else { return didFinish }
        let didValidateNextPage = await validateSchoolCourseNextPage(authStore: authStore, featureStore: featureStore)
        guard didValidateNextPage else { return false }
        return await validateSchoolCourseFilterVariants(authStore: authStore, featureStore: featureStore)
    }

    private static func validateCouponFilterPages(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        for filter in SharedCouponFilter.allCases {
            featureStore.updateCouponFilter(filter)
            log("feature=coupons filter=\(filter.rawValue) status=start")
            await featureStore.loadCoupons(force: true)
            let state = await authStore.syncStateFromManager()
            if case .siteVerificationRequired(_, let siteName, _) = state {
                setPendingFeature("coupons")
                log("feature=coupons filter=\(filter.rawValue) status=siteVerificationRequired site=\(siteName)")
                return false
            }
            if let message = featureStore.errorMessage(for: .coupons) {
                log("feature=coupons status=failed errorPresent=true \(failureSummary(message)) filter=\(filter.rawValue)")
                return true
            }
            log(couponSummary(filter: filter, featureStore: featureStore))

            guard featureStore.canLoadMoreCoupons else { continue }
            log("feature=coupons filter=\(filter.rawValue) page=next status=start")
            await featureStore.loadMoreCoupons()
            let nextState = await authStore.syncStateFromManager()
            if case .siteVerificationRequired(_, let siteName, _) = nextState {
                setPendingFeature("coupons")
                log("feature=coupons filter=\(filter.rawValue) page=next status=siteVerificationRequired site=\(siteName)")
                return false
            }
            if let message = featureStore.errorMessage(for: .coupons) {
                log("feature=coupons status=failed errorPresent=true \(failureSummary(message)) filter=\(filter.rawValue) page=next")
                return true
            }
            log(couponSummary(filter: filter, featureStore: featureStore))
        }
        return true
    }

    private static func validateSchoolCourseNextPage(authStore: AuthStore, featureStore: FeatureStore) async -> Bool {
        guard featureStore.canLoadMoreSchoolCourses else {
            log("feature=schoolCourses page=next status=skipped reason=noMoreResults")
            return true
        }
        log("feature=schoolCourses page=next status=start")
        await featureStore.loadMoreSchoolCourses()
        let state = await authStore.syncStateFromManager()
        if case .siteVerificationRequired(_, let siteName, _) = state {
            setPendingFeature("schoolCourses")
            log("feature=schoolCourses page=next status=siteVerificationRequired site=\(siteName)")
            return false
        }
        if let message = featureStore.errorMessage(for: .schoolCourses) {
            log("feature=schoolCourses status=failed errorPresent=true \(failureSummary(message)) page=next")
            return true
        }
        log(
            [
                "feature=schoolCourses",
                "page=\(featureStore.schoolCoursePage)",
                "status=success",
                "loaded=\(featureStore.schoolCourses.count)",
                "total=\(featureStore.totalSchoolCourses)",
                "termPresent=\(!featureStore.schoolCourseTermCode.isEmpty)",
                "canLoadMore=\(featureStore.canLoadMoreSchoolCourses)",
            ].joined(separator: " ")
        )
        return true
    }

    private static func validateSchoolCourseFilterVariants(
        authStore: AuthStore,
        featureStore: FeatureStore
    ) async -> Bool {
        for probe in schoolCourseProbes {
            applySchoolCourseProbe(probe, featureStore: featureStore)
            log("feature=schoolCourses variant=\(probe.label) status=start")
            await featureStore.searchSchoolCourses()
            let state = await authStore.syncStateFromManager()
            if case .siteVerificationRequired(_, let siteName, _) = state {
                setPendingFeature("schoolCourses")
                log("feature=schoolCourses variant=\(probe.label) status=siteVerificationRequired site=\(siteName)")
                return false
            }
            if let message = featureStore.errorMessage(for: .schoolCourses) {
                log("feature=schoolCourses variant=\(probe.label) status=failed errorPresent=true \(failureSummary(message))")
                continue
            }
            log(schoolCourseSummary(variant: probe.label, featureStore: featureStore))
        }
        return true
    }

    private static func couponSummary(filter: SharedCouponFilter, featureStore: FeatureStore) -> String {
        [
            "feature=coupons",
            "filter=\(filter.rawValue)",
            "page=\(featureStore.couponPage)",
            "status=success",
            "loaded=\(featureStore.coupons.count)",
            "total=\(featureStore.totalCoupons)",
            "canLoadMore=\(featureStore.canLoadMoreCoupons)",
        ].joined(separator: " ")
    }

    private static var schoolCourseProbes: [SchoolCourseProbe] {
        [
            SchoolCourseProbe(
                label: "courseNameCommon",
                nameQuery: "\u{6570}\u{5B66}",
                teacherQuery: "",
                campusCode: "",
                weekday: 0
            ),
            SchoolCourseProbe(
                label: "campus1",
                nameQuery: "",
                teacherQuery: "",
                campusCode: "1",
                weekday: 0
            ),
            SchoolCourseProbe(
                label: "weekday1",
                nameQuery: "",
                teacherQuery: "",
                campusCode: "",
                weekday: 1
            ),
            SchoolCourseProbe(
                label: "teacherNoMatch",
                nameQuery: "",
                teacherQuery: "NoSuchTeacherForDebugValidation",
                campusCode: "",
                weekday: 0
            ),
        ]
    }

    private static func applySchoolCourseProbe(_ probe: SchoolCourseProbe, featureStore: FeatureStore) {
        featureStore.updateSchoolCourseNameQuery(probe.nameQuery)
        featureStore.updateSchoolCourseTeacherQuery(probe.teacherQuery)
        featureStore.updateSchoolCourseCampusCode(probe.campusCode)
        featureStore.updateSchoolCourseWeekday(probe.weekday)
    }

    private static func schoolCourseSummary(variant: String, featureStore: FeatureStore) -> String {
        [
            "feature=schoolCourses",
            "variant=\(variant)",
            "page=\(featureStore.schoolCoursePage)",
            "status=success",
            "loaded=\(featureStore.schoolCourses.count)",
            "total=\(featureStore.totalSchoolCourses)",
            "termPresent=\(!featureStore.schoolCourseTermCode.isEmpty)",
            "canLoadMore=\(featureStore.canLoadMoreSchoolCourses)",
        ].joined(separator: " ")
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
        if let message = errorMessage() {
            log("feature=\(name) status=failed errorPresent=true \(failureSummary(message))")
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

    private static func failureSummary(_ message: String) -> String {
        let normalized = message.lowercased()
        let kind: String
        if normalized.contains("cas/safety") ||
            normalized.contains("补授权") ||
            normalized.contains("siteverification") {
            kind = "auth"
        } else if normalized.contains("http ") {
            kind = "http"
        } else if normalized.contains("返回空数据") {
            kind = "empty"
        } else if normalized.contains("payload must be a json object") {
            kind = "nonJson"
        } else if normalized.contains("expected, got") {
            kind = "unexpectedBody"
        } else if normalized.contains("missing term code") {
            kind = "missingTerm"
        } else if normalized.contains("missing qxfbkccx") {
            kind = "missingCourseModule"
        } else if normalized.contains("missing totalsize") {
            kind = "missingTotal"
        } else if normalized.contains("missing rows") {
            kind = "missingRows"
        } else if normalized.contains("service returned code") {
            kind = "serviceCode"
        } else {
            kind = "other"
        }
        var details = "errorKind=\(kind) errorLength=\(message.count)"
        if let shape = message
            .split(separator: " ")
            .first(where: { $0.hasPrefix("shape=") }) {
            details += " \(shape)"
        }
        return details
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
