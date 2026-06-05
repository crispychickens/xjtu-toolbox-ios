import SwiftUI

@main
struct XJTUToolboxIOSApp: App {
    @StateObject private var authStore: AuthStore
    @StateObject private var featureStore: FeatureStore
    @StateObject private var router = Router()

    init() {
        let dependencies = AppDependencyFactory.makeDefault()
        let authStore = AuthStore(authManager: dependencies.authManager)
        _authStore = StateObject(wrappedValue: authStore)
        _featureStore = StateObject(
            wrappedValue: FeatureStore(
                provider: dependencies.featureProvider,
                syncAuthState: { await authStore.syncStateFromManager() }
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(authStore)
                .environmentObject(featureStore)
                .environmentObject(router)
        }
    }
}
