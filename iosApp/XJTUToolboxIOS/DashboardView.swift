import SwiftUI

struct DashboardView: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 12) {
                    if let message = featureStore.errorMessage(for: .dashboard) {
                        FeatureErrorBanner(feature: .dashboard, message: message)
                    }

                    if let dashboard = featureStore.dashboard {
                        DashboardSectionHeader(title: "今日课程")
                        if dashboard.todayCourses.isEmpty {
                            DashboardCard(title: "今日无课", subtitle: "今天没有课程安排，可下拉刷新更新日程", systemImage: "calendar.badge.checkmark")
                        } else {
                            ForEach(dashboard.todayCourses) { course in
                                CourseRow(course: course)
                            }
                        }

                        DashboardSectionHeader(title: "校园卡")
                        if let card = dashboard.campusCard {
                            DashboardCard(
                                title: "余额 \(String(format: "%.2f", card.balanceYuan)) 元",
                                subtitle: card.holderName,
                                systemImage: "creditcard"
                            )
                        } else {
                            DashboardCard(
                                title: "校园卡暂不可用",
                                subtitle: "可能需要在学辅页完成补授权或等待数据加载",
                                systemImage: "creditcard"
                            )
                        }

                        DashboardSectionHeader(title: "通知公告")
                        if dashboard.notices.isEmpty {
                            DashboardCard(title: "暂无通知", subtitle: "可下拉刷新更新公告", systemImage: "bell")
                        } else {
                            ForEach(dashboard.notices) { notice in
                                NoticeRow(notice: notice)
                            }
                        }
                    } else if featureStore.isLoading(.dashboard) {
                        LoadingStateView(title: "正在加载首页")
                    } else {
                        WaitingStateView(title: "等待加载首页")
                    }
                }
                .padding()
            }
            .navigationTitle("首页")
            .background(Color(.systemGroupedBackground))
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

    var body: some View {
        NavigationStack {
            List {
                if let message = featureStore.errorMessage(for: .schedule) {
                    FeatureErrorRow(feature: .schedule, message: message)
                }

                if let schedule = featureStore.schedule {
                    Section("概览") {
                        ScheduleSummaryView(
                            schedule: schedule,
                            visibleCourseCount: featureStore.filteredScheduleCourses.count,
                            substantiveTextbookCount: featureStore.substantiveTextbookCount,
                            selectedDay: featureStore.scheduleDayFilter
                        )

                        Picker("查看", selection: scheduleDayBinding) {
                            ForEach(featureStore.availableScheduleDayFilters, id: \.self) { filter in
                                Text(filter).tag(filter)
                            }
                        }
                    }

                    Section("课程") {
                        let courses = featureStore.filteredScheduleCourses
                        if courses.isEmpty {
                            Text("当前日期暂无课程")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(courses) { course in
                                CourseRow(
                                    course: course,
                                    showsDay: featureStore.scheduleDayFilter == "全部课程"
                                )
                            }
                        }
                    }

                    Section("考试") {
                        if schedule.exams.isEmpty {
                            Text("暂无考试安排")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(schedule.exams) { exam in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(exam.courseName)
                                        .font(.headline)
                                    Text("\(exam.time) · \(exam.location)")
                                        .font(.subheadline)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }

                    Section("教材") {
                        if schedule.textbooks.isEmpty {
                            Text("暂无教材信息")
                                .foregroundStyle(.secondary)
                        } else {
                            Picker("筛选", selection: textbookFilterBinding) {
                                ForEach(featureStore.availableTextbookFilters, id: \.self) { filter in
                                    Text(filter).tag(filter)
                                }
                            }

                            let textbooks = featureStore.filteredTextbooks
                            if textbooks.isEmpty {
                                Text("当前筛选下暂无教材信息")
                                    .foregroundStyle(.secondary)
                            } else {
                                Text("\(featureStore.textbookFilter) · \(textbooks.count) 条")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                ForEach(textbooks) { textbook in
                                    TextbookRow(textbook: textbook)
                                }
                            }
                        }
                    }
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

    private var scheduleDayBinding: Binding<String> {
        Binding(
            get: { featureStore.scheduleDayFilter },
            set: { featureStore.updateScheduleDayFilter($0) }
        )
    }

    private var textbookFilterBinding: Binding<String> {
        Binding(
            get: { featureStore.textbookFilter },
            set: { featureStore.updateTextbookFilter($0) }
        )
    }
}

private struct ScheduleSummaryView: View {
    let schedule: SharedScheduleSnapshot
    let visibleCourseCount: Int
    let substantiveTextbookCount: Int
    let selectedDay: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                ScheduleMetricView(
                    title: "当前显示",
                    value: "\(visibleCourseCount) 门",
                    systemImage: "calendar"
                )
                ScheduleMetricView(
                    title: "全部课程",
                    value: "\(schedule.courses.count) 门",
                    systemImage: "list.bullet"
                )
                ScheduleMetricView(
                    title: "考试",
                    value: "\(schedule.exams.count) 场",
                    systemImage: "doc.text"
                )
            }

            HStack(spacing: 12) {
                ScheduleMetricView(
                    title: "有教材",
                    value: "\(substantiveTextbookCount)/\(schedule.textbooks.count)",
                    systemImage: "book"
                )
                ScheduleMetricView(
                    title: "视图",
                    value: selectedDay,
                    systemImage: "line.3.horizontal.decrease.circle"
                )
            }
        }
        .padding(.vertical, 4)
    }
}

private struct ScheduleMetricView: View {
    let title: String
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
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
                let detail = [textbook.author, textbook.publisher, textbook.isbn].filter { !$0.isEmpty }
                if !detail.isEmpty {
                    Text(detail.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}

struct ToolsView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        NavigationStack {
            List {
                GradeSection()
                CampusCardSection()
                EmptyRoomSection()
                NoticeSection()
            }
            .listStyle(.insetGrouped)
            .navigationTitle("学辅")
            .task {
                if XjtuLaunchArguments.shouldAutoLoadFeatures {
                    await loadFeatures()
                }
            }
            .refreshable {
                await loadFeatures(force: true)
            }
        }
    }

    private func loadFeatures(force: Bool = false) async {
        await featureStore.loadGrades(force: force)
        if !authStore.hasPendingSiteVerification {
            await featureStore.loadCampusCard(force: force)
        }
        await featureStore.loadEmptyRooms(force: force)
        await featureStore.loadNotices(force: force)
    }
}

struct ProfileView: View {
    @EnvironmentObject private var authStore: AuthStore

    var body: some View {
        NavigationStack {
            List {
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
}

struct SettingsView: View {
    @EnvironmentObject private var authStore: AuthStore
    @EnvironmentObject private var featureStore: FeatureStore
    @State private var isConfirmingCacheClear = false

    var body: some View {
        Form {
            Section("网络") {
                Picker("连接模式", selection: accessModeBinding) {
                    Text("自动检测").tag(SharedAccessMode.automatic)
                    Text("强制直连").tag(SharedAccessMode.normal)
                    Text("强制 WebVPN").tag(SharedAccessMode.webvpn)
                }
            }

            Section("数据") {
                Button(role: .destructive) {
                    isConfirmingCacheClear = true
                } label: {
                    Label("清除功能缓存", systemImage: "trash")
                }
                Text("只清除首页、日程、学辅等功能数据缓存；不会移除 Keychain 中保存的登录凭据。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("关于") {
                LabeledContent("版本", value: "3.5.1 iOS migration")
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

private struct GradeSection: View {
    @EnvironmentObject private var featureStore: FeatureStore

    var body: some View {
        Section("成绩") {
            if let grades = featureStore.grades {
                GradeSummaryView(
                    grades: grades,
                    needsAttentionCount: featureStore.gradeNeedsAttentionCount,
                    highestGradePoint: featureStore.highestGradePoint
                )

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
                        GradeRow(grade: grade)
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
    let grades: SharedGradeSnapshot
    let needsAttentionCount: Int
    let highestGradePoint: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                GradeMetricView(
                    title: "GPA",
                    value: grades.weightedGpa.map { String(format: "%.2f", $0) } ?? "-",
                    systemImage: "chart.line.uptrend.xyaxis"
                )
                GradeMetricView(
                    title: "总学分",
                    value: String(format: "%.1f", grades.totalCredits),
                    systemImage: "graduationcap"
                )
                GradeMetricView(
                    title: "课程数",
                    value: "\(grades.grades.count)",
                    systemImage: "list.number"
                )
            }

            HStack(spacing: 12) {
                GradeMetricView(
                    title: "最高绩点",
                    value: highestGradePoint.map { String(format: "%.2f", $0) } ?? "-",
                    systemImage: "star"
                )
                GradeMetricView(
                    title: "需关注",
                    value: "\(needsAttentionCount)",
                    systemImage: "exclamationmark.circle"
                )
            }

            Text("GPA 按课程学分加权计算；“需关注”按绩点为 0、数字成绩低于 60 或失败类文本识别。")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

private struct GradeMetricView: View {
    let title: String
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct GradeRow: View {
    let grade: SharedGradeItem

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
        }
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
                            Label("正在加载更多流水", systemImage: "arrow.clockwise")
                        } else {
                            Label("加载更多流水", systemImage: "chevron.down")
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
                    value: "\(card.transactions.count) 条",
                    systemImage: "list.bullet.rectangle"
                )
                CampusCardMetricView(
                    title: "支出",
                    value: "\(String(format: "%.2f", loadedExpenseTotal)) 元",
                    systemImage: "arrow.up.right"
                )
                CampusCardMetricView(
                    title: "收入",
                    value: "\(String(format: "%.2f", loadedIncomeTotal)) 元",
                    systemImage: "arrow.down.left"
                )
            }
        }
        .padding(.vertical, 4)
    }
}

private struct CampusCardMetricView: View {
    let title: String
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
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
                ForEach(rooms) { room in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(room.name)
                            .font(.headline)
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

            DatePicker("日期", selection: dateBinding, displayedComponents: .date)

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

            Text("\(featureStore.emptyRoomCampus) · \(featureStore.emptyRoomBuilding) · 第 \(featureStore.emptyRoomStartSection)-\(featureStore.emptyRoomEndSection) 节")
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
                    Text("\(featureStore.noticeSource) · \(notices.count) 条")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ForEach(notices) { notice in
                        NoticeRow(notice: notice)
                    }
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
        case .emptyRooms:
            return "重试空闲教室"
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
        VStack(alignment: .leading, spacing: 4) {
            Text(notice.title)
                .font(.headline)
            Text([notice.source, notice.date].compactMap { $0 }.joined(separator: " · "))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }
}

private struct DashboardCard: View {
    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 42, height: 42)
                .background(Color.accentColor.opacity(0.13), in: RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct DashboardSectionHeader: View {
    let title: String

    var body: some View {
        HStack {
            Text(title)
                .font(.headline)
            Spacer()
        }
        .padding(.top, 4)
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
