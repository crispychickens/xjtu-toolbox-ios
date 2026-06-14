import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct RootView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var featureStore: FeatureStore
    @EnvironmentObject private var router: Router
    @State private var didApplyLaunchArguments = false
    @State private var autoBeganSiteVerificationSites: Set<String> = []
    @State private var pendingSiteVerificationSiteName: String?

    var body: some View {
        Group {
            switch authStore.state {
            case .anonymous, .authenticating, .passwordInvalidated:
                LoginView()
            case .siteVerificationRequired:
                if authStore.shouldShowInlineSiteVerification {
                    MainTabView()
                        .safeAreaInset(edge: .top, spacing: 0) {
                            SiteVerificationBanner()
                        }
                } else {
                    LoginView()
                }
            case .awaitingCaptcha(let challenge):
                CaptchaView(challenge: challenge)
            case .awaitingMfa:
                MfaView()
            case .awaitingAccountChoice(let challenge):
                AccountChoiceView(challenge: challenge)
            case .awaitingBrowserAuth(let request):
                BrowserAuthView(request: request)
            case .authenticated:
                MainTabView()
            }
        }
        .onChange(of: authStore.state) { newState in
            if case .authenticated = newState {
                let siteName = pendingSiteVerificationSiteName
                pendingSiteVerificationSiteName = nil
                if let siteName {
                    Task {
                        await featureStore.resumeAfterSiteVerification(
                            siteName: siteName,
                            selectedTab: router.selectedTab
                        )
                    }
                } else {
                    featureStore.prepareForAuthenticatedSession()
                }
            }
            if case .siteVerificationRequired(_, let siteName, _) = newState,
               XjtuLaunchArguments.shouldAutoBeginSiteVerification,
               !autoBeganSiteVerificationSites.contains(siteName) {
                pendingSiteVerificationSiteName = siteName
                autoBeganSiteVerificationSites.insert(siteName)
                Task { await authStore.beginSiteVerification() }
            } else if case .siteVerificationRequired(_, let siteName, _) = newState {
                pendingSiteVerificationSiteName = siteName
            }
        }
        .task {
            await authStore.refresh()
            guard !didApplyLaunchArguments else { return }
            didApplyLaunchArguments = true
            await authStore.previewAccountChoiceIfAllowed()
            await authStore.previewAutoLoginIfAllowed()
            await authStore.autoBeginSiteVerificationIfAllowed()
        }
    }
}

private extension String {
    var siteDisplayName: String {
        switch self {
        case "campus_card":
            return "校园卡"
        case "schedule":
            return "日程"
        case "grade":
            return "成绩"
        case "library":
            return "图书馆座位"
        case "coupon":
            return "加餐券"
        case "jwapp", "jwxt":
            return "教务"
        default:
            return self
        }
    }
}

struct SiteVerificationBanner: View {
    @EnvironmentObject private var authStore: AuthStore

    var body: some View {
        if case .siteVerificationRequired(_, let siteName, let message) = authStore.state {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "person.badge.key")
                    .font(.title3)
                    .foregroundStyle(.blue)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 4) {
                    Text("\(siteName.siteDisplayName) 需要补授权")
                        .font(.subheadline.weight(.semibold))
                    if let errorMessage = authStore.errorMessage {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .fixedSize(horizontal: false, vertical: true)
                    } else {
                        Text(siteVerificationSummary(message: message, siteName: siteName))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                Spacer(minLength: 8)

                Button {
                    Task { await authStore.beginSiteVerification() }
                } label: {
                    if authStore.isBusy {
                        ProgressView()
                    } else {
                        Text("继续")
                            .font(.subheadline.weight(.semibold))
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(authStore.isBusy)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.regularMaterial)
            .overlay(alignment: .bottom) {
                Divider()
            }
        }
    }

    private func siteVerificationSummary(message: String, siteName: String) -> String {
        switch siteName {
        case "campus_card":
            return "当前登录未退出，日程等已加载内容可继续查看；校园卡需要单独完成一次授权。"
        case "schedule", "grade", "jwapp", "jwxt":
            return "当前登录未退出；教务系统要求重新确认身份，完成后会继续加载相关数据。"
        case "library":
            return "当前登录未退出；图书馆座位系统要求单独确认身份，完成后会继续加载座位数据。"
        case "coupon":
            return "当前登录未退出；加餐券系统要求单独确认身份，完成后会继续加载券数据。"
        default:
            return message
        }
    }
}

struct LoginView: View {
    @EnvironmentObject private var authStore: AuthStore
    private let dependencyMode = XjtuLaunchArguments.dependencyMode

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("学号 / 手机号", text: $authStore.username)
                        .textInputAutocapitalization(.never)
                    SecureField("密码", text: $authStore.password)
                } header: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("西安交通大学")
                            .font(.title2.weight(.semibold))
                        Text("统一身份认证")
                            .font(.subheadline)
                    }
                    .textCase(nil)
                }

                if let message = authStore.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    if case .siteVerificationRequired(_, let siteName, _) = authStore.state {
                        Button {
                            Task { await authStore.beginSiteVerification() }
                        } label: {
                            HStack {
                                Spacer()
                                if authStore.isBusy {
                                    ProgressView()
                                } else {
                                    Text("继续完成 \(siteName.siteDisplayName) 验证")
                                        .fontWeight(.semibold)
                                }
                                Spacer()
                            }
                        }
                        .disabled(authStore.isBusy)
                    }

                    Button {
                        Task { await authStore.login() }
                    } label: {
                        HStack {
                            Spacer()
                            if authStore.isBusy {
                                ProgressView()
                            } else {
                                Text("登录")
                                    .fontWeight(.semibold)
                            }
                            Spacer()
                        }
                    }
                    .disabled(authStore.isBusy)

                    if dependencyMode.allowsBrowserAuthHandoff {
                        Button {
                            Task { await authStore.beginBrowserAuth() }
                        } label: {
                            HStack {
                                Spacer()
                                Text("使用学校网页登录")
                                Spacer()
                            }
                        }
                        .disabled(authStore.isBusy)
                    } else if let notice = dependencyMode.browserAuthNotice {
                        Text(notice)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("岱宗盒子")
        }
    }
}

struct CaptchaView: View {
    @EnvironmentObject private var authStore: AuthStore
    let challenge: SharedCaptchaChallenge

    private var imageData: Data? {
        Data(base64Encoded: challenge.imageBase64)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    captchaImage
                    TextField("图形验证码", text: $authStore.captchaCode)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("西安交通大学")
                            .font(.title2.weight(.semibold))
                        Text("请输入图形验证码")
                            .font(.subheadline)
                    }
                    .textCase(nil)
                }

                if let message = authStore.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        Task { await authStore.submitCaptcha() }
                    } label: {
                        HStack {
                            Spacer()
                            if authStore.isBusy {
                                ProgressView()
                            } else {
                                Text("继续登录")
                                    .fontWeight(.semibold)
                            }
                            Spacer()
                        }
                    }
                    .disabled(authStore.isBusy || authStore.captchaCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    Button("取消") {
                        Task { await authStore.logout() }
                    }
                }
            }
            .navigationTitle("图形验证码")
        }
    }

    @ViewBuilder
    private var captchaImage: some View {
        #if canImport(UIKit)
        if let imageData, let image = UIImage(data: imageData) {
            HStack {
                Spacer()
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 80)
                    .accessibilityLabel("图形验证码")
                Spacer()
            }
        } else {
            Text("验证码图片加载失败")
                .foregroundStyle(.red)
        }
        #else
        Text("当前平台不支持显示验证码图片")
            .foregroundStyle(.red)
        #endif
    }
}

struct AccountChoiceView: View {
    @EnvironmentObject private var authStore: AuthStore
    let challenge: SharedAccountChoiceChallenge

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("账号类型", selection: $authStore.selectedAccountChoiceId) {
                        ForEach(sortedChoices) { choice in
                            Text(choiceTitle(choice))
                                .tag(choice.id)
                        }
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("西安交通大学")
                            .font(.title2.weight(.semibold))
                        Text("请选择账号类型")
                            .font(.subheadline)
                    }
                    .textCase(nil)
                } footer: {
                    Text("默认优先本科生账号；如学校返回的选项与你实际身份不符，请手动切换后继续。")
                }

                if let message = authStore.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button {
                        Task { await authStore.submitAccountChoice() }
                    } label: {
                        HStack {
                            Spacer()
                            if authStore.isBusy {
                                ProgressView()
                            } else {
                                Text("继续登录")
                                    .fontWeight(.semibold)
                            }
                            Spacer()
                        }
                    }
                    .disabled(authStore.isBusy || authStore.selectedAccountChoiceId.isEmpty)

                    Button("取消") {
                        Task { await authStore.logout() }
                    }
                }
            }
            .navigationTitle("账号类型")
        }
    }

    private var sortedChoices: [SharedAccountChoice] {
        challenge.choices.sorted { lhs, rhs in
            accountPriority(lhs.accountType) < accountPriority(rhs.accountType)
        }
    }

    private func choiceTitle(_ choice: SharedAccountChoice) -> String {
        switch choice.accountType {
        case .undergraduate:
            return "\(choice.displayName)（本科生）"
        case .postgraduate:
            return "\(choice.displayName)（研究生）"
        case .unknown:
            return choice.displayName
        }
    }

    private func accountPriority(_ type: SharedAccountType) -> Int {
        switch type {
        case .undergraduate:
            return 0
        case .postgraduate:
            return 1
        case .unknown:
            return 2
        }
    }
}

struct BrowserAuthView: View {
    @EnvironmentObject private var authStore: AuthStore
    @State private var presenter = OfficialBrowserAuthPresenter()
    @State private var isOpeningOfficialLogin = false
    let request: SharedBrowserAuthRequest

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        Task { await openOfficialLogin() }
                    } label: {
                        HStack {
                            Spacer()
                            if authStore.isBusy || isOpeningOfficialLogin {
                                ProgressView()
                            } else {
                                Text("打开学校登录页")
                                    .fontWeight(.semibold)
                            }
                            Spacer()
                        }
                    }
                    .disabled(authStore.isBusy || isOpeningOfficialLogin)
                } header: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("西安交通大学")
                            .font(.title2.weight(.semibold))
                        Text("官方网页登录")
                            .font(.subheadline)
                    }
                    .textCase(nil)
                }

                if let message = authStore.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button("取消") {
                        Task { await authStore.logout() }
                    }
                }
            }
            .navigationTitle("统一身份认证")
        }
    }

    private func openOfficialLogin() async {
        guard !isOpeningOfficialLogin else { return }
        isOpeningOfficialLogin = true
        defer { isOpeningOfficialLogin = false }
        do {
            let callbackURL = try await presenter.authenticate(request: request)
            await authStore.resumeBrowserAuth(callbackURL: callbackURL)
        } catch {
            authStore.errorMessage = "网页登录未完成"
        }
    }
}

struct MfaView: View {
    @EnvironmentObject private var authStore: AuthStore

    private var maskedPhone: String {
        if case .awaitingMfa(let phone) = authStore.state { return phone }
        return ""
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("6 位验证码", text: $authStore.mfaCode)
                        .keyboardType(.numberPad)
                } header: {
                    Text("验证码已发送至 \(maskedPhone)")
                        .textCase(nil)
                }

                if let message = authStore.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }

                Section {
                    Button("验证") {
                        Task { await authStore.submitMfa() }
                    }
                    .disabled(authStore.isBusy || authStore.mfaCode.count != 6)
                }
            }
            .navigationTitle("手机验证")
        }
    }
}

struct MainTabView: View {
    @EnvironmentObject private var router: Router

    var body: some View {
        TabView(selection: $router.selectedTab) {
            DashboardView()
                .tabItem { Label(AppTab.home.title, systemImage: AppTab.home.systemImage) }
                .tag(AppTab.home)

            ScheduleHomeView()
                .tabItem { Label(AppTab.schedule.title, systemImage: AppTab.schedule.systemImage) }
                .tag(AppTab.schedule)

            ToolsView()
                .tabItem { Label(AppTab.tools.title, systemImage: AppTab.tools.systemImage) }
                .tag(AppTab.tools)

            ProfileView()
                .tabItem { Label(AppTab.profile.title, systemImage: AppTab.profile.systemImage) }
                .tag(AppTab.profile)
        }
    }
}
