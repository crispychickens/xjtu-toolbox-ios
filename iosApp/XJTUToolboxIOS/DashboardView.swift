import SwiftUI

struct DashboardView: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        NavigationStack {
            List {
                if let message = featureStore.errorMessage(for: .dashboard) {
                    Section {
                        FeatureErrorBanner(feature: .dashboard, message: message)
                    }
                }

                if let dashboard = featureStore.dashboard {
                    Section("今日课程") {
                        if dashboard.todayCourses.isEmpty {
                            Text("今日无课")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(dashboard.todayCourses) { course in
                                CourseRow(course: course)
                            }
                        }
                    }

                    Section("校园卡") {
                        if let card = dashboard.campusCard {
                            LabeledContent("余额", value: "\(String(format: "%.2f", card.balanceYuan)) 元")
                            if !card.holderName.isEmpty {
                                LabeledContent("持卡人", value: card.holderName)
                            }
                        } else {
                            Text("暂不可用，可能需要在学辅页完成补授权")
                                .foregroundStyle(.secondary)
                        }
                    }

                    Section("通知公告") {
                        if dashboard.notices.isEmpty {
                            Text("暂无通知")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(dashboard.notices) { notice in
                                NoticeRow(notice: notice)
                            }
                        }
                    }
                } else if featureStore.isLoading(.dashboard) {
                    LoadingStateView(title: "正在加载首页")
                } else {
                    WaitingStateView(title: "等待加载首页")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("首页")
            .task {
                if XjtuLaunchArguments.shouldAutoLoadFeatures {
                    await featureStore.loadDashboard()
                }
            }
            .refreshable {
                await featureStore.loadDashboard(force: true)
            }
        }
    }
}

struct ScheduleHomeView: View {
    @EnvironmentObject private var featureStore: FeatureStore
    @State private var selectedView: ScheduleDestination

    init() {
        _selectedView = State(
            initialValue: ScheduleDestination(rawValue: XjtuLaunchArguments.initialScheduleViewName ?? "") ?? .courses
        )
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("查看", selection: $selectedView) {
                        ForEach(ScheduleDestination.allCases) { destination in
                            Text(destination.title).tag(destination)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }

                if let message = featureStore.errorMessage(for: .schedule) {
                    FeatureErrorRow(feature: .schedule, message: message)
                }

                if let schedule = featureStore.schedule {
                    Section("概览") {
                        ScheduleSummaryView(
                            schedule: schedule,
                            destination: selectedView,
                            visibleCourseCount: featureStore.filteredScheduleCourses.count,
                            substantiveTextbookCount: featureStore.substantiveTextbookCount,
                            selectedDay: featureStore.scheduleDayFilter,
                            selectedWeek: featureStore.scheduleWeekFilterLabel
                        )

                        if selectedView == .courses {
                            Picker("教学周", selection: scheduleWeekBinding) {
                                Text("全部周次").tag(Int?.none)
                                ForEach(featureStore.availableScheduleWeekFilters, id: \.self) { week in
                                    Text("第 \(week) 周").tag(Optional(week))
                                }
                            }

                            Picker("星期", selection: scheduleDayBinding) {
                                ForEach(featureStore.availableScheduleDayFilters, id: \.self) { filter in
                                    Text(filter).tag(filter)
                                }
                            }
                        } else if selectedView == .textbooks, !schedule.textbooks.isEmpty {
                            Picker("筛选", selection: textbookFilterBinding) {
                                ForEach(featureStore.availableTextbookFilters, id: \.self) { filter in
                                    Text(filter).tag(filter)
                                }
                            }
                        }
                    }

                    selectedContent(schedule)
                } else if featureStore.isLoading(.schedule) {
                    LoadingStateView(title: "正在加载日程")
                } else {
                    WaitingStateView(title: "等待加载日程")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("日程")
            .task {
                if XjtuLaunchArguments.shouldAutoLoadFeatures {
                    await featureStore.loadSchedule()
                }
            }
            .refreshable {
                await featureStore.loadSchedule(force: true)
            }
        }
    }

    @ViewBuilder
    private func selectedContent(_ schedule: SharedScheduleSnapshot) -> some View {
        switch selectedView {
        case .courses:
            Section("课程") {
                let courses = featureStore.filteredScheduleCourses
                if courses.isEmpty {
                    Text("当前周次和星期筛选下暂无课程")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(courses) { course in
                        CourseRow(
                            course: course,
                            showsDay: featureStore.scheduleShowsAllDays
                        )
                    }
                }
            }
        case .exams:
            Section("考试") {
                let exams = featureStore.sortedScheduleExams
                if exams.isEmpty {
                    Text("暂无考试安排")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(exams) { exam in
                        ExamRow(exam: exam)
                    }
                }
            }
        case .textbooks:
            Section("教材") {
                if schedule.textbooks.isEmpty {
                    Text("暂无教材信息")
                        .foregroundStyle(.secondary)
                } else {
                    let textbooks = featureStore.filteredTextbooks
                    if textbooks.isEmpty {
                        Text("当前筛选下暂无教材信息")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(textbooks) { textbook in
                            TextbookRow(textbook: textbook)
                        }
                    }
                }
            }
        }
    }

    private var scheduleDayBinding: Binding<String> {
        Binding(
            get: { featureStore.scheduleDayFilter },
            set: { featureStore.updateScheduleDayFilter($0) }
        )
    }

    private var scheduleWeekBinding: Binding<Int?> {
        Binding(
            get: { featureStore.scheduleWeekFilter },
            set: { featureStore.updateScheduleWeekFilter($0) }
        )
    }

    private var textbookFilterBinding: Binding<String> {
        Binding(
            get: { featureStore.textbookFilter },
            set: { featureStore.updateTextbookFilter($0) }
        )
    }
}

private enum ScheduleDestination: String, CaseIterable, Identifiable {
    case courses
    case exams
    case textbooks

    var id: String { rawValue }

    var title: String {
        switch self {
        case .courses:
            return "课程"
        case .exams:
            return "考试"
        case .textbooks:
            return "教材"
        }
    }
}

private struct ScheduleSummaryView: View {
    let schedule: SharedScheduleSnapshot
    let destination: ScheduleDestination
    let visibleCourseCount: Int
    let substantiveTextbookCount: Int
    let selectedDay: String
    let selectedWeek: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(primarySummary)
                .font(.headline)
            Text(secondarySummary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var primarySummary: String {
        switch destination {
        case .courses:
            return "\(visibleCourseCount) 门课程"
        case .exams:
            return "\(schedule.exams.count) 场考试"
        case .textbooks:
            return "\(substantiveTextbookCount) 门课程有教材"
        }
    }

    private var secondarySummary: String {
        switch destination {
        case .courses:
            return "\(selectedWeek) · \(selectedDay) · 全部 \(schedule.courses.count) 门"
        case .exams:
            return schedule.exams.isEmpty ? "当前学期暂无考试安排" : "当前学期考试安排"
        case .textbooks:
            return "\(substantiveTextbookCount)/\(schedule.textbooks.count) 门课程有实质教材信息"
        }
    }
}

private struct TextbookRow: View {
    let textbook: SharedTextbookItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(textbook.courseName)
                .font(.headline)
            Text(textbook.textbookName)
                .font(.subheadline)
                .foregroundStyle(textbook.hasSubstantiveTextbook ? .primary : .secondary)
            if textbook.hasSubstantiveTextbook {
                let purchaseDetail = [
                    textbookEditionDetail,
                    labeledTextbookValue("定价", textbook.price),
                ].compactMap { $0 }
                if !purchaseDetail.isEmpty {
                    Text(purchaseDetail.joined(separator: " · "))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                let detail = [textbook.author, textbook.publisher, textbook.isbn].filter { !$0.isEmpty }
                if !detail.isEmpty {
                    Text(detail.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func labeledTextbookValue(_ label: String, _ value: String) -> String? {
        guard let trimmed = trimmedTextbookValue(value) else { return nil }
        return "\(label) \(trimmed)"
    }

    private var textbookEditionDetail: String? {
        guard let edition = trimmedTextbookValue(textbook.edition) else { return nil }
        return textbook.textbookName.localizedCaseInsensitiveContains(edition) ? nil : edition
    }

    private func trimmedTextbookValue(_ value: String) -> String? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private struct ExamRow: View {
    let exam: SharedExamItem

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(exam.courseName)
                    .font(.headline)
                Spacer()
                if !exam.seatNumber.isEmpty {
                    Text("\(exam.seatNumber) 号")
                        .font(.headline)
                }
            }

            Text(examDateTime)
                .font(.subheadline)
                .foregroundStyle(.primary)

            let details = [exam.location, exam.courseCode].filter { !$0.isEmpty }
            if !details.isEmpty {
                Text(details.joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var examDateTime: String {
        let components = [exam.examDate, exam.examTime].filter { !$0.isEmpty }
        return components.isEmpty ? exam.time : components.joined(separator: " · ")
    }
}

struct ToolsView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var featureStore: FeatureStore
    @State private var selectedTool: ToolDestination

    init() {
        _selectedTool = State(
            initialValue: ToolDestination(rawValue: XjtuLaunchArguments.initialToolName ?? "") ?? .grades
        )
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ScrollViewReader { proxy in
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(ToolDestination.allCases) { destination in
                                    Button {
                                        selectedTool = destination
                                    } label: {
                                        Text(destination.title)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(selectedTool == destination ? Color.white : Color.primary)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 8)
                                            .background(
                                                selectedTool == destination ? Color.accentColor : Color.secondary.opacity(0.12),
                                                in: Capsule()
                                            )
                                    }
                                    .buttonStyle(.plain)
                                    .id(destination.id)
                                }
                            }
                        }
                        .onAppear {
                            proxy.scrollTo(selectedTool.id, anchor: .center)
                        }
                        .onChange(of: selectedTool) { destination in
                            withAnimation {
                                proxy.scrollTo(destination.id, anchor: .center)
                            }
                        }
                    }
                }

                selectedSection
            }
            .listStyle(.insetGrouped)
            .navigationTitle("学辅")
            .task(id: selectedTool) {
                if XjtuLaunchArguments.shouldAutoLoadFeatures {
                    await loadSelectedFeature()
                }
            }
            .refreshable {
                await loadSelectedFeature(force: true)
            }
        }
    }

    @ViewBuilder
    private var selectedSection: some View {
        switch selectedTool {
        case .grades:
            GradeSection()
        case .campusCard:
            CampusCardSection()
        case .coupons:
            CouponSection()
        case .librarySeats:
            LibrarySeatSection()
        case .schoolCourses:
            SchoolCourseSection()
        case .emptyRooms:
            EmptyRoomSection()
        case .notices:
            NoticeSection()
        }
    }

    private func loadSelectedFeature(force: Bool = false) async {
        switch selectedTool {
        case .grades:
            await featureStore.loadGrades(force: force)
        case .campusCard where !authStore.hasPendingSiteVerification:
            await featureStore.loadCampusCard(force: force)
        case .coupons where !authStore.hasPendingSiteVerification:
            await featureStore.loadCoupons(force: force)
        case .librarySeats where !authStore.hasPendingSiteVerification:
            await featureStore.loadLibrarySeats(force: force)
        case .schoolCourses where featureStore.didSearchSchoolCourses && !authStore.hasPendingSiteVerification:
            await featureStore.searchSchoolCourses()
        case .emptyRooms:
            await featureStore.loadEmptyRooms(force: force)
        case .notices:
            await featureStore.loadNotices(force: force)
        case .campusCard, .coupons, .librarySeats, .schoolCourses:
            break
        }
    }
}

private enum ToolDestination: String, CaseIterable, Identifiable {
    case grades
    case campusCard
    case coupons
    case librarySeats
    case schoolCourses
    case emptyRooms
    case notices

    var id: String { rawValue }

    var title: String {
        switch self {
        case .grades:
            return "成绩"
        case .campusCard:
            return "校园卡"
        case .coupons:
            return "加餐券"
        case .librarySeats:
            return "图书馆"
        case .schoolCourses:
            return "查课程"
        case .emptyRooms:
            return "空教室"
        case .notices:
            return "通知"
        }
    }
}

struct ProfileView: View {
    @EnvironmentObject private var authStore: AuthStore

    var body: some View {
        NavigationStack {
            List {
                Section("登录状态") {
                    Label {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(authStatusTitle)
                                .font(.headline)
                            Text(authStatusMessage)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: authStatusIcon)
                    }

                    if case .siteVerificationRequired(_, let siteName, _) = authStore.state {
                        Button {
                            Task { await authStore.beginSiteVerification() }
                        } label: {
                            if authStore.isBusy {
                                Label("正在继续验证", systemImage: "arrow.clockwise")
                            } else {
                                Label("继续完成 \(siteDisplayName(for: siteName)) 验证", systemImage: "person.badge.key")
                            }
                        }
                        .disabled(authStore.isBusy)
                    }

                    LabeledContent("连接模式", value: authStore.accessMode.displayName)
                }

                Section("账号") {
                    LabeledContent("登录账号", value: authenticatedUsername)
                    LabeledContent("当前模式", value: dependencyMode.displayName)
                    LabeledContent("认证来源", value: dependencyMode.authenticationSourceLabel)
                    LabeledContent("功能数据", value: dependencyMode.featureDataSourceLabel)

                    if let validationNotice = dependencyMode.validationNotice {
                        Text(validationNotice)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                NavigationLink {
                    SettingsView()
                } label: {
                    Label("设置", systemImage: "gearshape")
                }

                Button(role: .destructive) {
                    Task { await authStore.logout() }
                } label: {
                    Label("退出登录", systemImage: "rectangle.portrait.and.arrow.right")
                }
            }
            .navigationTitle("我的")
        }
    }

    private var dependencyMode: XjtuDependencyMode {
        XjtuLaunchArguments.dependencyMode
    }

    private var authenticatedUsername: String {
        authStore.displayUsername ?? "-"
    }

    private var authStatusTitle: String {
        switch authStore.state {
        case .authenticated:
            return "已登录"
        case .siteVerificationRequired(_, let siteName, _):
            return "\(siteDisplayName(for: siteName)) 待补授权"
        case .authenticating:
            return "正在登录"
        case .awaitingCaptcha:
            return "等待图形验证码"
        case .awaitingMfa:
            return "等待短信验证"
        case .awaitingAccountChoice:
            return "等待账号类型确认"
        case .awaitingBrowserAuth:
            return "等待网页登录回跳"
        case .passwordInvalidated:
            return "密码需要重新输入"
        case .anonymous:
            return "未登录"
        }
    }

    private var authStatusMessage: String {
        switch authStore.state {
        case .authenticated:
            return "当前全局登录态可用；功能页会按需复用或重新建立站点会话。"
        case .siteVerificationRequired(_, let siteName, _):
            return "当前没有被登出；\(siteDisplayName(for: siteName)) 要求单独确认身份，完成后会继续加载相关数据。"
        case .authenticating:
            return "正在向认证服务提交登录请求，请不要重复点击。"
        case .awaitingCaptcha:
            return "请输入页面显示的图形验证码；验证码不会缓存。"
        case .awaitingMfa:
            return "请输入短信验证码；验证码不会写入缓存、日志或文档。"
        case .awaitingAccountChoice:
            return "请选择 CAS 返回的账号类型后继续。"
        case .awaitingBrowserAuth:
            return "等待官方网页登录返回 App；当前真实验证模式通常不会启用此路径。"
        case .passwordInvalidated:
            return "保存的密码已失效，需要重新通过登录表单输入。"
        case .anonymous:
            return "当前没有可用登录态。"
        }
    }

    private var authStatusIcon: String {
        switch authStore.state {
        case .authenticated:
            return "checkmark.seal"
        case .siteVerificationRequired:
            return "person.badge.key"
        case .authenticating:
            return "arrow.clockwise"
        case .awaitingCaptcha, .awaitingMfa, .awaitingAccountChoice, .awaitingBrowserAuth:
            return "exclamationmark.circle"
        case .passwordInvalidated:
            return "key.slash"
        case .anonymous:
            return "person.crop.circle.badge.questionmark"
        }
    }

    private func siteDisplayName(for siteName: String) -> String {
        switch siteName {
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
            return siteName
        }
    }
}

private extension SharedAccessMode {
    var displayName: String {
        switch self {
        case .automatic:
            return "自动检测"
        case .normal:
            return "强制直连"
        case .webvpn:
            return "强制 WebVPN"
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var featureStore: FeatureStore
    @State private var isConfirmingCacheClear = false
    private let dependencyMode = XjtuLaunchArguments.dependencyMode
    private let appVersion = AppVersionInfo.current

    var body: some View {
        Form {
            Section("当前验证模式") {
                LabeledContent("运行模式", value: dependencyMode.displayName)
                LabeledContent("认证来源", value: dependencyMode.authenticationSourceLabel)
                LabeledContent("功能数据", value: dependencyMode.featureDataSourceLabel)

                if let validationNotice = dependencyMode.validationNotice {
                    Text(validationNotice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section("网络") {
                Picker("连接模式", selection: accessModeBinding) {
                    Text("自动检测").tag(SharedAccessMode.automatic)
                    Text("强制直连").tag(SharedAccessMode.normal)
                    Text("强制 WebVPN").tag(SharedAccessMode.webvpn)
                }

                Text("切换连接模式会使已有站点会话失效，后续功能加载会按新模式重新建立会话。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("凭据与缓存") {
                LabeledContent("登录凭据", value: "Keychain")
                LabeledContent("验证码", value: "不缓存")
                Text("账号密码只通过登录表单和 Keychain 使用；验证码、CAS ticket、OAuth token 不应写入启动参数、日志、截图、源码或文档。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Button(role: .destructive) {
                    isConfirmingCacheClear = true
                } label: {
                    Label("清除功能缓存", systemImage: "trash")
                }
                Text("清除功能内存缓存和允许持久化的日程、通知、空教室缓存；成绩、校园卡和首页不会写入 UserDefaults。Keychain 登录凭据不会被移除。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("关于") {
                LabeledContent("iOS 版本", value: appVersion.displayVersion)
                LabeledContent("迁移基线", value: "Android 3.5.1")
                Text("默认启动保持预览安全模式；真实 CAS、教务和一卡通验证只在显式验证 scheme 或启动参数下启用。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("设置")
        .task {
            await authStore.refresh()
        }
        .alert("缓存已清除", isPresented: cacheClearedBinding) {
            Button("确定", role: .cancel) {}
        }
        .confirmationDialog("清除功能缓存？", isPresented: $isConfirmingCacheClear, titleVisibility: .visible) {
            Button("清除功能缓存", role: .destructive) {
                featureStore.clearCachedData()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("这会删除已缓存的功能数据并重置当前页面加载状态，但不会删除账号、密码或登录会话。")
        }
    }

    private var accessModeBinding: Binding<SharedAccessMode> {
        Binding(
            get: { authStore.accessMode },
            set: { mode in
                Task { await authStore.setAccessMode(mode) }
            }
        )
    }

    private var cacheClearedBinding: Binding<Bool> {
        Binding(
            get: { featureStore.didClearCache },
            set: { newValue in
                if !newValue {
                    featureStore.acknowledgeCacheClear()
                }
            }
        )
    }
}

private struct AppVersionInfo {
    let shortVersion: String
    let build: String

    var displayVersion: String {
        if build.isEmpty || build == shortVersion {
            return shortVersion
        }
        return "\(shortVersion) (\(build))"
    }

    static var current: AppVersionInfo {
        let info = Bundle.main.infoDictionary
        let shortVersion = info?["CFBundleShortVersionString"] as? String
        let build = info?["CFBundleVersion"] as? String
        return AppVersionInfo(
            shortVersion: normalized(shortVersion) ?? "-",
            build: normalized(build) ?? ""
        )
    }

    private static func normalized(_ value: String?) -> String? {
        value?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            ? value?.trimmingCharacters(in: .whitespacesAndNewlines)
            : nil
    }
}

private struct GradeSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("成绩") {
            if featureStore.grades != nil {
                GradeSummaryView(
                    gradeCount: featureStore.gradeScopeGrades.count,
                    weightedGpa: featureStore.gradeScopeWeightedGpa,
                    totalCredits: featureStore.gradeScopeTotalCredits,
                    needsAttentionCount: featureStore.gradeNeedsAttentionCount,
                    highestGradePoint: featureStore.highestGradePoint
                )

                if featureStore.shouldShowGradeTermPicker {
                    Picker("学期", selection: gradeTermBinding) {
                        ForEach(featureStore.availableGradeTerms, id: \.self) { term in
                            Text(GradeTermDisplay.name(for: term)).tag(term)
                        }
                    }
                }

                Picker("筛选", selection: gradeFilterBinding) {
                    ForEach(featureStore.availableGradeFilters, id: \.self) { filter in
                        Text(filter).tag(filter)
                    }
                }

                Picker("排序", selection: gradeSortBinding) {
                    ForEach(featureStore.availableGradeSorts, id: \.self) { sort in
                        Text(sort).tag(sort)
                    }
                }

                let visibleGrades = featureStore.filteredGrades
                if visibleGrades.isEmpty {
                    Text("当前筛选下暂无成绩")
                        .foregroundStyle(.secondary)
                } else {
                    Text("\(featureStore.gradeFilter) · \(visibleGrades.count) 门")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(visibleGrades) { grade in
                        if grade.hasDetail {
                            NavigationLink {
                                GradeDetailView(grade: grade)
                            } label: {
                                GradeRow(
                                    grade: grade,
                                    showsTerm: featureStore.gradeTerm == GradeDisplayOptions.allTermsOption
                                )
                            }
                        } else {
                            GradeRow(
                                grade: grade,
                                showsTerm: featureStore.gradeTerm == GradeDisplayOptions.allTermsOption
                            )
                        }
                    }
                }
            } else {
                FeatureSectionPlaceholder(
                    feature: .grades,
                    loadingTitle: "正在加载成绩",
                    waitingTitle: "等待加载成绩"
                )
            }
        }
    }

    private var gradeTermBinding: Binding<String> {
        Binding(
            get: { featureStore.gradeTerm },
            set: { featureStore.updateGradeTerm($0) }
        )
    }

    private var gradeFilterBinding: Binding<String> {
        Binding(
            get: { featureStore.gradeFilter },
            set: { featureStore.updateGradeFilter($0) }
        )
    }

    private var gradeSortBinding: Binding<String> {
        Binding(
            get: { featureStore.gradeSort },
            set: { featureStore.updateGradeSort($0) }
        )
    }
}

private struct GradeSummaryView: View {
    let gradeCount: Int
    let weightedGpa: Double?
    let totalCredits: Double
    let needsAttentionCount: Int
    let highestGradePoint: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(primarySummary)
                .font(.headline)
            Text(secondarySummary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var primarySummary: String {
        let gpa = weightedGpa.map { String(format: "%.2f", $0) } ?? "-"
        return "GPA \(gpa) · \(String(format: "%.1f", totalCredits)) 学分 · \(gradeCount) 门"
    }

    private var secondarySummary: String {
        let highest = highestGradePoint.map { String(format: "%.2f", $0) } ?? "-"
        return "最高绩点 \(highest) · 需关注 \(needsAttentionCount)"
    }
}

private struct GradeDetailView: View {
    @EnvironmentObject private var featureStore: FeatureStore
    let grade: SharedGradeItem

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(grade.courseName)
                        .font(.title3.weight(.semibold))
                    HStack(alignment: .firstTextBaseline, spacing: 20) {
                        GradeDetailHeadline(title: "成绩", value: detail?.score ?? grade.score)
                        GradeDetailHeadline(
                            title: "绩点",
                            value: String(format: "%.2f", detail?.gradePoint ?? grade.gradePoint)
                        )
                        GradeDetailHeadline(
                            title: "学分",
                            value: String(format: "%.1f", detail?.credit ?? grade.credit)
                        )
                    }
                }
                .padding(.vertical, 4)
            }

            if let detail {
                Section("分项成绩") {
                    if detail.items.isEmpty {
                        Text("该课程未返回分项成绩")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(detail.items) { item in
                            HStack(alignment: .firstTextBaseline) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(item.name)
                                    if item.percent > 0 {
                                        Text(String(format: "占比 %.0f%%", item.percent * 100))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Text(item.score.isEmpty ? "-" : item.score)
                                    .fontWeight(.semibold)
                            }
                        }
                    }
                }

                Section("课程信息") {
                    if !grade.termCode.isEmpty {
                        LabeledContent("学期", value: GradeTermDisplay.name(for: grade.termCode))
                    }
                    if !detail.examType.isEmpty {
                        LabeledContent("考试类型", value: detail.examType)
                    }
                    if let courseProperty = detail.courseProperty, !courseProperty.isEmpty {
                        LabeledContent("课程性质", value: courseProperty)
                    }
                    if !detail.examProperty.isEmpty {
                        LabeledContent("考试性质", value: detail.examProperty)
                    }
                    LabeledContent("通过状态", value: detail.isPassed ? "已通过" : "未通过")
                    if detail.isReplacement {
                        LabeledContent("成绩状态", value: "替代成绩")
                    }
                    if let reason = detail.specificReason, !reason.isEmpty {
                        LabeledContent("说明", value: reason)
                    }
                }
            } else if let error = featureStore.gradeDetailError(for: grade) {
                Section {
                    Text(error)
                        .foregroundStyle(.red)
                    Button("重试") {
                        Task { await featureStore.loadGradeDetail(for: grade, force: true) }
                    }
                    .disabled(featureStore.isLoadingGradeDetail(for: grade))
                }
            } else {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("正在加载成绩详情")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("成绩详情")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await featureStore.loadGradeDetail(for: grade)
        }
    }

    private var detail: SharedGradeDetail? {
        featureStore.gradeDetail(for: grade)
    }
}

private struct GradeDetailHeadline: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value.isEmpty ? "-" : value)
                .font(.title3.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct GradeRow: View {
    let grade: SharedGradeItem
    let showsTerm: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(grade.courseName)
                    .font(.headline)
                Spacer()
                Text(grade.score)
                    .font(.headline)
                    .foregroundStyle(grade.needsAttention ? .red : .primary)
            }
            Text("\(String(format: "%.1f", grade.credit)) 学分 · 绩点 \(String(format: "%.2f", grade.gradePoint))")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if showsTerm, !grade.termCode.isEmpty {
                Text(GradeTermDisplay.name(for: grade.termCode))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private enum GradeTermDisplay {
    static func name(for termCode: String) -> String {
        guard termCode != GradeDisplayOptions.allTermsOption else {
            return termCode
        }
        let parts = termCode.split(separator: "-")
        guard parts.count == 3 else {
            return termCode.isEmpty ? "学期未知" : termCode
        }
        let semester: String
        switch parts[2] {
        case "1":
            semester = "秋季学期"
        case "2":
            semester = "春季学期"
        case "3":
            semester = "夏季学期"
        default:
            semester = "第 \(parts[2]) 学期"
        }
        return "\(parts[0])-\(parts[1]) \(semester)"
    }
}

private extension SharedGradeItem {
    var needsAttention: Bool {
        if gradePoint <= 0 {
            return true
        }
        let normalizedScore = score.trimmingCharacters(in: .whitespacesAndNewlines)
        if let numericScore = Double(normalizedScore) {
            return numericScore < 60
        }
        return normalizedScore.contains("不及格") ||
            normalizedScore.contains("不通过") ||
            normalizedScore.localizedCaseInsensitiveContains("fail")
    }
}

private struct CampusCardSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("校园卡") {
            if let card = featureStore.campusCard {
                CampusCardSummaryView(
                    card: card,
                    loadedExpenseTotal: featureStore.loadedCampusCardExpenseTotal,
                    loadedIncomeTotal: featureStore.loadedCampusCardIncomeTotal
                )

                if card.transactions.isEmpty {
                    Text("暂无近期流水")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(card.transactions) { transaction in
                        CampusCardTransactionRow(transaction: transaction)
                    }
                }

                if let message = featureStore.errorMessage(for: .campusCard) {
                    FeatureSectionError(
                        feature: .campusCard,
                        message: message,
                        retryTitle: "重试校园卡"
                    )
                }

                if featureStore.canLoadMoreCampusCardTransactions {
                    Button {
                        Task { await featureStore.loadMoreCampusCardTransactions() }
                    } label: {
                        if featureStore.isLoading(.campusCard) {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("正在加载更多流水")
                            }
                        } else {
                            Text("加载更多流水")
                        }
                    }
                    .disabled(featureStore.isLoading(.campusCard))
                }
            } else {
                FeatureSectionPlaceholder(
                    feature: .campusCard,
                    loadingTitle: "正在加载校园卡",
                    waitingTitle: "等待加载校园卡"
                )
            }
        }
    }
}

private struct CampusCardSummaryView: View {
    let card: SharedCampusCardSnapshot
    let loadedExpenseTotal: Double
    let loadedIncomeTotal: Double

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            LabeledContent("余额", value: "\(String(format: "%.2f", card.info.balanceYuan)) 元")
            LabeledContent("持卡人", value: card.info.holderName.isEmpty ? "-" : card.info.holderName)

            HStack(spacing: 12) {
                CampusCardMetricView(
                    title: "已加载流水",
                    value: "\(card.transactions.count)/\(card.totalTransactions) 条"
                )
                CampusCardMetricView(
                    title: "支出",
                    value: "\(String(format: "%.2f", loadedExpenseTotal)) 元"
                )
                CampusCardMetricView(
                    title: "收入",
                    value: "\(String(format: "%.2f", loadedIncomeTotal)) 元"
                )
            }
        }
        .padding(.vertical, 4)
    }
}

private struct CampusCardMetricView: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct CouponSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("加餐券") {
            Picker("状态", selection: couponFilterBinding) {
                ForEach(featureStore.availableCouponFilters) { filter in
                    Text(filter.title).tag(filter)
                }
            }
            .pickerStyle(.segmented)

            if featureStore.didLoadCoupons {
                couponSummary
            }

            if featureStore.coupons.isEmpty {
                if featureStore.didLoadCoupons {
                    Text(emptyTitle)
                        .foregroundStyle(.secondary)
                } else if let message = featureStore.errorMessage(for: .coupons) {
                    FeatureSectionError(
                        feature: .coupons,
                        message: message,
                        retryTitle: "重试加餐券"
                    )
                } else if featureStore.isLoading(.coupons) {
                    LoadingStateView(title: "正在加载加餐券")
                } else {
                    WaitingStateView(title: "等待加载加餐券")
                }
            } else {
                ForEach(featureStore.coupons) { coupon in
                    CouponRow(coupon: coupon, filter: featureStore.couponFilter)
                }
            }

            if let message = featureStore.errorMessage(for: .coupons), !featureStore.coupons.isEmpty {
                FeatureSectionError(
                    feature: .coupons,
                    message: message,
                    retryTitle: "重试加餐券"
                )
            }

            if featureStore.canLoadMoreCoupons {
                Button {
                    Task { await featureStore.loadMoreCoupons() }
                } label: {
                    if featureStore.isLoading(.coupons) {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在加载更多加餐券")
                        }
                    } else {
                        Text("加载更多加餐券")
                    }
                }
                .disabled(featureStore.isLoading(.coupons))
            }
        }
    }

    private var couponSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            LabeledContent("当前状态", value: featureStore.couponFilter.title)
            LabeledContent("已加载", value: "\(featureStore.coupons.count)/\(featureStore.totalCoupons) 张")
            if featureStore.couponFilter == .usable {
                LabeledContent("可用余额", value: "\(String(format: "%.2f", featureStore.loadedCouponValueTotal)) 元")
            }
        }
        .padding(.vertical, 4)
    }

    private var emptyTitle: String {
        switch featureStore.couponFilter {
        case .available:
            return "暂无待使用加餐券"
        case .usable:
            return "暂无可使用加餐券"
        case .usedUp:
            return "暂无已用完加餐券"
        case .expired:
            return "暂无已过期加餐券"
        }
    }

    private var couponFilterBinding: Binding<SharedCouponFilter> {
        Binding(
            get: { featureStore.couponFilter },
            set: { filter in
                featureStore.updateCouponFilter(filter)
                Task { await featureStore.loadCoupons(force: true) }
            }
        )
    }
}

private struct CouponRow: View {
    let coupon: SharedCouponRecord
    let filter: SharedCouponFilter

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(coupon.voucherName.isEmpty ? "加餐券" : coupon.voucherName)
                        .font(.headline)
                    Text(coupon.typeName.isEmpty ? filter.title : coupon.typeName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(statusTitle)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(statusColor)
            }

            HStack {
                LabeledContent("剩余", value: "\(String(format: "%.2f", coupon.leftAmountYuan)) 元")
                Spacer()
                LabeledContent("面额", value: "\(String(format: "%.2f", coupon.amountYuan)) 元")
            }
            .font(.footnote)

            HStack {
                Text("次数 \(coupon.leftCount)")
                Spacer()
                Text(validityText)
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var statusTitle: String {
        switch filter {
        case .available:
            return "待使用"
        case .usable:
            return coupon.leftCount > 0 || coupon.leftAmountYuan > 0 ? "可使用" : "已用完"
        case .usedUp:
            return "已用完"
        case .expired:
            return "已过期"
        }
    }

    private var statusColor: Color {
        switch filter {
        case .usable:
            return .green
        case .available, .usedUp, .expired:
            return .secondary
        }
    }

    private var validityText: String {
        let start = coupon.startDate.isEmpty ? "开始未知" : coupon.startDate
        let end = coupon.endDate.isEmpty ? "结束未知" : coupon.endDate
        return "\(start) 至 \(end)"
    }
}

private struct LibrarySeatSection: View {
    @EnvironmentObject private var featureStore: FeatureStore
    @State private var seatInput = ""
    @State private var pendingSeat: String?

    var body: some View {
        Section("图书馆座位") {
            if let snapshot = featureStore.librarySeats {
                librarySummary(snapshot)
                areaPicker(snapshot)
                bookingInput
                recommendedAreas(snapshot)
                availableSeats(snapshot)
            } else if let message = featureStore.errorMessage(for: .librarySeats) {
                FeatureSectionError(
                    feature: .librarySeats,
                    message: message,
                    retryTitle: "重试图书馆座位"
                )
            } else if featureStore.isLoading(.librarySeats) {
                LoadingStateView(title: "正在加载图书馆座位")
            } else {
                WaitingStateView(title: "等待加载图书馆座位")
            }

            if let result = featureStore.libraryBookingResult {
                Text(result.message)
                    .font(.footnote)
                    .foregroundStyle(result.success ? Color.secondary : Color.red)
            }
        }
        .confirmationDialog(
            "预约座位",
            isPresented: Binding(
                get: { pendingSeat != nil },
                set: { if !$0 { pendingSeat = nil } }
            ),
            titleVisibility: .visible
        ) {
            if let pendingSeat {
                Button("确认预约 \(pendingSeat)") {
                    let seat = pendingSeat
                    self.pendingSeat = nil
                    Task { await featureStore.bookLibrarySeat(seatId: seat, allowSwap: true) }
                }
            }
            Button("取消", role: .cancel) {
                pendingSeat = nil
            }
        } message: {
            if let booking = featureStore.librarySeats?.myBooking {
                Text("当前已预约 \(booking.seatId)，确认后会尝试换座。")
            } else {
                Text("确认后将向图书馆座位系统提交预约。")
            }
        }
    }

    private func librarySummary(_ snapshot: SharedLibrarySeatSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let booking = snapshot.myBooking {
                HStack(alignment: .firstTextBaseline) {
                    Text("当前预约")
                        .foregroundStyle(.secondary)
                    Spacer()
                    Text(booking.seatId)
                        .font(.headline)
                }
                if let areaName = booking.areaName {
                    Text([areaName, booking.statusText].compactMap { $0 }.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("当前没有预约")
                    .foregroundStyle(.secondary)
            }

            if let area = featureStore.selectedLibrarySeatArea {
                HStack(alignment: .firstTextBaseline) {
                    Text(area.name)
                        .font(.headline)
                    Spacer()
                    Text("\(area.available) 空座")
                        .font(.subheadline.weight(.semibold))
                }
                Text(areaSeatCountText(area))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func areaPicker(_ snapshot: SharedLibrarySeatSnapshot) -> some View {
        Picker("区域", selection: areaBinding) {
            ForEach(snapshot.areas) { area in
                Text(areaPickerTitle(area)).tag(area.code)
            }
        }
    }

    private var bookingInput: some View {
        HStack(spacing: 8) {
            TextField("座位号", text: $seatInput)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button(featureStore.isBookingLibrarySeat ? "提交中" : "预约") {
                let seat = seatInput.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                guard !seat.isEmpty else { return }
                pendingSeat = seat
            }
            .disabled(featureStore.isBookingLibrarySeat || seatInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private func recommendedAreas(_ snapshot: SharedLibrarySeatSnapshot) -> some View {
        Group {
            if !snapshot.recommendedAreas.isEmpty {
                Text("空座较多区域")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(snapshot.recommendedAreas) { area in
                    Button {
                        featureStore.updateLibrarySeatArea(area.code)
                        Task { await featureStore.loadLibrarySeats(force: true) }
                    } label: {
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(area.name)
                                    .foregroundStyle(.primary)
                                Text(area.floor)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(area.available) 空座")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private func availableSeats(_ snapshot: SharedLibrarySeatSnapshot) -> some View {
        Group {
            let seats = snapshot.seats.filter(\.available)
            if seats.isEmpty {
                Text("当前区域暂无可预约座位")
                    .foregroundStyle(.secondary)
            } else {
                Text("可预约座位")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(Array(seats.prefix(16))) { seat in
                    Button {
                        pendingSeat = seat.seatId
                    } label: {
                        HStack {
                            Text(seat.seatId)
                                .foregroundStyle(.primary)
                            Spacer()
                            Text("预约")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .disabled(featureStore.isBookingLibrarySeat)
                }
                if seats.count > 16 {
                    Text("已显示前 16 个可预约座位，可输入座位号预约其他座位")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var areaBinding: Binding<String> {
        Binding(
            get: { featureStore.librarySeatAreaCode ?? featureStore.librarySeats?.selectedAreaCode ?? "" },
            set: { areaCode in
                featureStore.updateLibrarySeatArea(areaCode)
                Task { await featureStore.loadLibrarySeats(force: true) }
            }
        )
    }

    private func areaPickerTitle(_ area: SharedLibraryAreaStats) -> String {
        area.total > 0 ? "\(area.name) 空座 \(area.available) · 座位 \(area.total)" : area.name
    }

    private func areaSeatCountText(_ area: SharedLibraryAreaStats) -> String {
        if area.total > 0 {
            return "\(area.floor) · 空座 \(area.available) 个 · 总座位 \(area.total) 个"
        }
        return "\(area.floor) · 暂无座位统计"
    }
}

private struct SchoolCourseSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("全校课程查询") {
            TextField("课程名", text: courseNameBinding)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            TextField("教师姓名", text: teacherBinding)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Picker("校区", selection: campusBinding) {
                ForEach(featureStore.availableSchoolCourseCampuses) { campus in
                    Text(campus.name).tag(campus.code)
                }
            }

            Picker("星期", selection: weekdayBinding) {
                ForEach(featureStore.availableSchoolCourseWeekdays, id: \.self) { weekday in
                    Text(featureStore.schoolCourseWeekdayTitle(weekday)).tag(weekday)
                }
            }

            Button {
                Task { await featureStore.searchSchoolCourses() }
            } label: {
                if featureStore.isLoading(.schoolCourses) {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在查询课程")
                    }
                } else {
                    Text("查询课程")
                }
            }
            .disabled(featureStore.isLoading(.schoolCourses))

            if featureStore.didSearchSchoolCourses {
                LabeledContent(
                    "查询结果",
                    value: "\(featureStore.schoolCourseTermCode) · 已加载 \(featureStore.schoolCourses.count)/\(featureStore.totalSchoolCourses) 门"
                )
                .font(.footnote)

                if featureStore.schoolCourses.isEmpty,
                   featureStore.errorMessage(for: .schoolCourses) == nil,
                   !featureStore.isLoading(.schoolCourses) {
                    Text("当前条件未查询到课程")
                        .foregroundStyle(.secondary)
                }
            } else if let message = featureStore.errorMessage(for: .schoolCourses) {
                FeatureSectionError(
                    feature: .schoolCourses,
                    message: message,
                    retryTitle: "重试课程查询"
                )
            } else {
                Text("按当前学期查询全校课程；请求只在点击查询或加载更多时发送。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            ForEach(featureStore.schoolCourses) { course in
                SchoolCourseRow(course: course)
            }

            if let message = featureStore.errorMessage(for: .schoolCourses),
               !featureStore.schoolCourses.isEmpty {
                FeatureSectionError(
                    feature: .schoolCourses,
                    message: message,
                    retryTitle: "重试课程查询"
                )
            }

            if featureStore.canLoadMoreSchoolCourses {
                Button {
                    Task { await featureStore.loadMoreSchoolCourses() }
                } label: {
                    if featureStore.isLoading(.schoolCourses) {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在加载更多课程")
                        }
                    } else {
                        Text("加载更多课程")
                    }
                }
                .disabled(featureStore.isLoading(.schoolCourses))
            }
        }
    }

    private var courseNameBinding: Binding<String> {
        Binding(
            get: { featureStore.schoolCourseNameQuery },
            set: { featureStore.updateSchoolCourseNameQuery($0) }
        )
    }

    private var teacherBinding: Binding<String> {
        Binding(
            get: { featureStore.schoolCourseTeacherQuery },
            set: { featureStore.updateSchoolCourseTeacherQuery($0) }
        )
    }

    private var campusBinding: Binding<String> {
        Binding(
            get: { featureStore.schoolCourseCampusCode },
            set: { featureStore.updateSchoolCourseCampusCode($0) }
        )
    }

    private var weekdayBinding: Binding<Int> {
        Binding(
            get: { featureStore.schoolCourseWeekday },
            set: { featureStore.updateSchoolCourseWeekday($0) }
        )
    }
}

private struct SchoolCourseRow: View {
    let course: SharedSchoolCourse

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(course.courseName)
                    .font(.headline)
                Spacer()
                if course.credit > 0 {
                    Text("\(String(format: "%.1f", course.credit)) 学分")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            let codeLine = [course.courseCode, course.sectionNumber.isEmpty ? "" : "课序 \(course.sectionNumber)"]
                .filter { !$0.isEmpty }
                .joined(separator: " · ")
            if !codeLine.isEmpty {
                Text(codeLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            let teachingLine = [course.teacher, course.department].filter { !$0.isEmpty }.joined(separator: " · ")
            if !teachingLine.isEmpty {
                Text(teachingLine)
                    .font(.subheadline)
            }

            if !course.scheduleLocation.isEmpty {
                Text(course.scheduleLocation)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack {
                if !course.campus.isEmpty {
                    Text(course.campus)
                }
                Spacer()
                if course.capacity > 0 {
                    Text("已选 \(course.enrollCount)/\(course.capacity)")
                } else if course.enrollCount > 0 {
                    Text("已选 \(course.enrollCount)")
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct EmptyRoomSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("空闲教室") {
            emptyRoomFilters

            let rooms = featureStore.filteredEmptyRooms
            if rooms.isEmpty {
                if featureStore.didLoadEmptyRooms {
                    Text("当前筛选暂无空闲教室")
                        .foregroundStyle(.secondary)
                } else if let message = featureStore.errorMessage(for: .emptyRooms) {
                    FeatureSectionError(
                        feature: .emptyRooms,
                        message: message,
                        retryTitle: "重试空闲教室"
                    )
                } else if featureStore.isLoading(.emptyRooms) {
                    LoadingStateView(title: "正在加载空闲教室")
                } else {
                    WaitingStateView(title: "等待加载空闲教室")
                }
            } else {
                Text("\(rooms.count) 间符合条件")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                ForEach(rooms) { room in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(room.name)
                                .font(.headline)
                            Spacer()
                            if room.capacity > 0 {
                                Text("\(room.capacity) 座")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Text("\(room.campus) · \(room.building) · 第 \(room.availableSections.map(String.init).joined(separator: ",")) 节")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var emptyRoomFilters: some View {
        Group {
            Picker("校区", selection: campusBinding) {
                ForEach(featureStore.availableEmptyRoomCampuses, id: \.self) { campus in
                    Text(campus).tag(campus)
                }
            }

            Picker("教学楼", selection: buildingBinding) {
                ForEach(featureStore.availableEmptyRoomBuildings, id: \.self) { building in
                    Text(building).tag(building)
                }
            }

            Picker("最低容量", selection: minimumCapacityBinding) {
                ForEach(featureStore.availableEmptyRoomMinimumCapacities, id: \.self) { capacity in
                    Text(capacity == 0 ? "不限容量" : "\(capacity) 座及以上").tag(capacity)
                }
            }

            DatePicker("日期", selection: dateBinding, displayedComponents: .date)
                .environment(\.locale, Locale(identifier: "zh_CN"))

            Picker("开始节次", selection: startSectionBinding) {
                ForEach(1...11, id: \.self) { section in
                    Text("第 \(section) 节").tag(section)
                }
            }

            Picker("结束节次", selection: endSectionBinding) {
                ForEach(1...11, id: \.self) { section in
                    Text("第 \(section) 节").tag(section)
                }
            }

            Text("\(featureStore.emptyRoomCampus) · \(featureStore.emptyRoomBuilding) · \(minimumCapacityLabel) · 第 \(featureStore.emptyRoomStartSection)-\(featureStore.emptyRoomEndSection) 节")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var campusBinding: Binding<String> {
        Binding(
            get: { featureStore.emptyRoomCampus },
            set: { campus in
                featureStore.updateEmptyRoomCampus(campus)
                Task { await featureStore.loadEmptyRooms(force: true) }
            }
        )
    }

    private var buildingBinding: Binding<String> {
        Binding(
            get: { featureStore.emptyRoomBuilding },
            set: { featureStore.updateEmptyRoomBuilding($0) }
        )
    }

    private var dateBinding: Binding<Date> {
        Binding(
            get: { featureStore.emptyRoomDate },
            set: { date in
                featureStore.updateEmptyRoomDate(date)
                Task { await featureStore.loadEmptyRooms(force: true) }
            }
        )
    }

    private var minimumCapacityBinding: Binding<Int> {
        Binding(
            get: { featureStore.emptyRoomMinimumCapacity },
            set: { featureStore.updateEmptyRoomMinimumCapacity($0) }
        )
    }

    private var minimumCapacityLabel: String {
        featureStore.emptyRoomMinimumCapacity == 0
            ? "不限容量"
            : "\(featureStore.emptyRoomMinimumCapacity) 座及以上"
    }

    private var startSectionBinding: Binding<Int> {
        Binding(
            get: { featureStore.emptyRoomStartSection },
            set: { section in
                featureStore.updateEmptyRoomStartSection(section)
                Task { await featureStore.loadEmptyRooms(force: true) }
            }
        )
    }

    private var endSectionBinding: Binding<Int> {
        Binding(
            get: { featureStore.emptyRoomEndSection },
            set: { section in
                featureStore.updateEmptyRoomEndSection(section)
                Task { await featureStore.loadEmptyRooms(force: true) }
            }
        )
    }
}

private struct NoticeSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("通知公告") {
            if featureStore.notices.isEmpty {
                if featureStore.didLoadNotices {
                    Text("暂无通知")
                        .foregroundStyle(.secondary)
                } else if let message = featureStore.errorMessage(for: .notices) {
                    FeatureSectionError(
                        feature: .notices,
                        message: message,
                        retryTitle: "重试通知"
                    )
                } else if featureStore.isLoading(.notices) {
                    LoadingStateView(title: "正在加载通知")
                } else {
                    WaitingStateView(title: "等待加载通知")
                }
            } else {
                Picker("来源", selection: noticeSourceBinding) {
                    ForEach(featureStore.availableNoticeSources, id: \.self) { source in
                        Text(source).tag(source)
                    }
                }

                let notices = featureStore.filteredNotices
                if notices.isEmpty {
                    Text("当前来源暂无通知")
                        .foregroundStyle(.secondary)
                } else {
                    Text(noticeCountSummary(visibleCount: notices.count))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(notices) { notice in
                        NoticeRow(notice: notice)
                    }
                }

                if let message = featureStore.errorMessage(for: .notices) {
                    FeatureSectionError(
                        feature: .notices,
                        message: message,
                        retryTitle: "重试通知"
                    )
                }

                if featureStore.canLoadMoreNotices {
                    Button {
                        Task { await featureStore.loadMoreNotices() }
                    } label: {
                        if featureStore.isLoading(.notices) {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text("正在加载更多通知")
                            }
                        } else {
                            Text("加载更多通知")
                        }
                    }
                    .disabled(featureStore.isLoading(.notices))
                }
            }
        }
    }

    private var noticeSourceBinding: Binding<String> {
        Binding(
            get: { featureStore.noticeSource },
            set: { featureStore.updateNoticeSource($0) }
        )
    }

    private func noticeCountSummary(visibleCount: Int) -> String {
        if featureStore.noticeShowsAllSources {
            return "已加载 \(featureStore.notices.count)/\(featureStore.totalNotices) 条"
        }
        return "\(featureStore.noticeSource) · 当前已加载 \(visibleCount) 条"
    }
}

private struct FeatureErrorRow: View {
    let feature: FeatureRequestKind
    let message: String

    var body: some View {
        Section {
            FeatureSectionError(
                feature: feature,
                message: message,
                retryTitle: "重试日程"
            )
        }
    }
}

private struct FeatureErrorBanner: View {
    @EnvironmentObject private var featureStore: FeatureStore
    let feature: FeatureRequestKind
    let message: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(message, systemImage: "exclamationmark.triangle")
                .font(.subheadline)
                .foregroundStyle(.red)
            Button {
                Task { await featureStore.retry(feature) }
            } label: {
                if featureStore.isLoading(feature) {
                    Label("正在重试", systemImage: "arrow.clockwise")
                } else {
                    Label("重试首页", systemImage: "arrow.clockwise")
                }
            }
            .font(.subheadline.weight(.semibold))
            .disabled(featureStore.isLoading(feature))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }
}

private struct FeatureSectionPlaceholder: View {
    @EnvironmentObject private var featureStore: FeatureStore
    let feature: FeatureRequestKind
    let loadingTitle: String
    let waitingTitle: String

    var body: some View {
        if let message = featureStore.errorMessage(for: feature) {
            FeatureSectionError(
                feature: feature,
                message: message,
                retryTitle: retryTitle
            )
        } else if featureStore.isLoading(feature) {
            LoadingStateView(title: loadingTitle)
        } else {
            WaitingStateView(title: waitingTitle)
        }
    }

    private var retryTitle: String {
        switch feature {
        case .grades:
            return "重试成绩"
        case .campusCard:
            return "重试校园卡"
        case .coupons:
            return "重试加餐券"
        case .emptyRooms:
            return "重试空闲教室"
        case .librarySeats:
            return "重试图书馆座位"
        case .schoolCourses:
            return "重试课程查询"
        case .notices:
            return "重试通知"
        case .dashboard:
            return "重试首页"
        case .schedule:
            return "重试日程"
        }
    }
}

private struct FeatureSectionError: View {
    @EnvironmentObject private var featureStore: FeatureStore
    let feature: FeatureRequestKind
    let message: String
    let retryTitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(message, systemImage: "exclamationmark.triangle")
                .foregroundStyle(.red)
            Button {
                Task { await featureStore.retry(feature) }
            } label: {
                if featureStore.isLoading(feature) {
                    Label("正在重试", systemImage: "arrow.clockwise")
                } else {
                    Label(retryTitle, systemImage: "arrow.clockwise")
                }
            }
            .font(.subheadline.weight(.semibold))
            .disabled(featureStore.isLoading(feature))
        }
        .padding(.vertical, 4)
    }
}

private struct CampusCardTransactionRow: View {
    let transaction: SharedCampusCardTransaction

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(transaction.merchant.isEmpty ? "校园卡流水" : transaction.merchant)
                    .font(.headline)
                Text(transaction.time.isEmpty ? "时间未知" : transaction.time)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let balance = transaction.balanceAfterYuan {
                    Text("交易后余额 \(String(format: "%.2f", balance)) 元")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text(amountText)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(transaction.isIncome ? .green : .primary)
        }
    }

    private var amountText: String {
        let sign = transaction.isIncome ? "+" : "-"
        return "\(sign)\(String(format: "%.2f", abs(transaction.amountYuan)))"
    }
}

private struct CourseRow: View {
    let course: SharedCourseItem
    let showsDay: Bool

    init(course: SharedCourseItem, showsDay: Bool = false) {
        self.course = course
        self.showsDay = showsDay
    }

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 2) {
                Text("\(course.startSection)")
                    .font(.headline)
                Text("-\(course.endSection)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(width: 42, height: 42)
            .background(Color.accentColor.opacity(0.13), in: RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 4) {
                Text(course.name)
                    .font(.headline)
                Text(courseSubtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if !course.weeks.isEmpty {
                    Text(course.weekSummary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var courseSubtitle: String {
        let parts = [
            showsDay ? course.dayName : nil,
            course.teacher,
            course.location
        ]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return parts.joined(separator: " · ")
    }
}

private extension SharedCourseItem {
    var dayName: String {
        switch dayOfWeek {
        case 1: return "周一"
        case 2: return "周二"
        case 3: return "周三"
        case 4: return "周四"
        case 5: return "周五"
        case 6: return "周六"
        case 7: return "周日"
        default: return "星期未知"
        }
    }

    var weekSummary: String {
        let sortedWeeks = Array(Set(weeks)).sorted()
        guard let first = sortedWeeks.first, let last = sortedWeeks.last else {
            return ""
        }
        if sortedWeeks.count == 1 {
            return "第 \(first) 周"
        }
        if sortedWeeks == Array(first...last) {
            return "第 \(first)-\(last) 周"
        }
        return "第 \(sortedWeeks.map(String.init).joined(separator: ",")) 周"
    }
}

private struct NoticeRow: View {
    let notice: SharedNoticeItem

    var body: some View {
        if let url = notice.webURL {
            Link(destination: url) {
                content
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("在浏览器中打开通知原文")
        } else {
            content
        }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(notice.title)
                .font(.headline)
            Text([notice.source, notice.date].compactMap { $0 }.joined(separator: " · "))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension SharedNoticeItem {
    var webURL: URL? {
        guard let components = URLComponents(string: link),
              let scheme = components.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              components.host?.isEmpty == false,
              components.user == nil,
              components.password == nil else {
            return nil
        }
        return components.url
    }
}

private struct LoadingStateView: View {
    let title: String

    var body: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text(title)
                .foregroundStyle(.secondary)
        }
    }
}

private struct WaitingStateView: View {
    let title: String

    var body: some View {
        Text(title)
            .foregroundStyle(.secondary)
    }
}
