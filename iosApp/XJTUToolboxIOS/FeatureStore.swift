import Foundation

@MainActor
final class FeatureStore: ObservableObject {
    @Published private(set) var dashboard: SharedDashboardSnapshot?
    @Published private(set) var schedule: SharedScheduleSnapshot?
    @Published private(set) var scheduleDayFilter = ScheduleDisplayOptions.allDaysOption
    @Published private(set) var textbookFilter = TextbookDisplayOptions.allTextbooksOption
    @Published private(set) var grades: SharedGradeSnapshot?
    @Published private(set) var gradeFilter = GradeDisplayOptions.allGradesOption
    @Published private(set) var gradeSort = GradeDisplayOptions.defaultSortOption
    @Published private(set) var campusCard: SharedCampusCardSnapshot?
    @Published private(set) var campusCardPage = 0
    @Published private(set) var canLoadMoreCampusCardTransactions = false
    @Published private(set) var notices: [SharedNoticeItem] = []
    @Published private(set) var noticeSource = NoticeFilterOptions.allSourcesOption
    @Published private(set) var emptyRooms: [SharedEmptyRoom] = []
    @Published private(set) var emptyRoomCampus = EmptyRoomFilterOptions.defaultCampus
    @Published private(set) var emptyRoomBuilding = EmptyRoomFilterOptions.allBuildingsOption
    @Published private(set) var emptyRoomDate = Date()
    @Published private(set) var emptyRoomStartSection = 1
    @Published private(set) var emptyRoomEndSection = 4
    @Published private(set) var didLoadNotices = false
    @Published private(set) var didLoadEmptyRooms = false
    @Published private(set) var isLoading = false
    @Published private(set) var loadingFeatures: Set<FeatureRequestKind> = []
    @Published private(set) var failedFeatureMessages: [FeatureRequestKind: String] = [:]
    @Published var errorMessage: String?
    @Published private(set) var didClearCache = false

    private let provider: SharedFeatureProviding
    private let syncAuthState: (() async -> SharedAuthState)?
    private let campusCardPageSize = 20
    private let forcedRefreshCacheClearCooldown: TimeInterval = 30
    private var lastProviderCacheClearAt: Date?
    private var activeLoadCount = 0
    private var autoAttemptedFeatures: Set<FeatureRequestKind> = []

    init(provider: SharedFeatureProviding, syncAuthState: (() async -> SharedAuthState)? = nil) {
        self.provider = provider
        self.syncAuthState = syncAuthState
    }

    var availableEmptyRoomCampuses: [String] {
        EmptyRoomFilterOptions.campuses
    }

    var availableEmptyRoomBuildings: [String] {
        [EmptyRoomFilterOptions.allBuildingsOption] + EmptyRoomFilterOptions.buildingsByCampus[emptyRoomCampus, default: []]
    }

    var filteredEmptyRooms: [SharedEmptyRoom] {
        guard emptyRoomBuilding != EmptyRoomFilterOptions.allBuildingsOption else {
            return emptyRooms
        }
        return emptyRooms.filter { $0.building == emptyRoomBuilding }
    }

    var availableScheduleDayFilters: [String] {
        ScheduleDisplayOptions.dayFilters
    }

    var filteredScheduleCourses: [SharedCourseItem] {
        guard let schedule else { return [] }
        let sortedCourses = schedule.courses.sorted(by: scheduleCoursePrecedes)
        guard let selectedDay = ScheduleDisplayOptions.dayOfWeek(for: scheduleDayFilter) else {
            return sortedCourses
        }
        return sortedCourses.filter { $0.dayOfWeek == selectedDay }
    }

    var availableTextbookFilters: [String] {
        TextbookDisplayOptions.filters
    }

    var filteredTextbooks: [SharedTextbookItem] {
        guard let schedule else { return [] }
        switch textbookFilter {
        case TextbookDisplayOptions.substantiveTextbooksOption:
            return schedule.textbooks.filter(\.hasSubstantiveTextbook)
        case TextbookDisplayOptions.noTextbookOption:
            return schedule.textbooks.filter { !$0.hasSubstantiveTextbook }
        default:
            return schedule.textbooks
        }
    }

    var substantiveTextbookCount: Int {
        schedule?.textbooks.filter(\.hasSubstantiveTextbook).count ?? 0
    }

    var loadedCampusCardExpenseTotal: Double {
        campusCard?.transactions
            .filter { !$0.isIncome }
            .reduce(0) { $0 + abs($1.amountYuan) } ?? 0
    }

    var loadedCampusCardIncomeTotal: Double {
        campusCard?.transactions
            .filter(\.isIncome)
            .reduce(0) { $0 + abs($1.amountYuan) } ?? 0
    }

    var availableNoticeSources: [String] {
        let sources = Set(notices.map(\.source).filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty })
        return [NoticeFilterOptions.allSourcesOption] + sources.sorted()
    }

    var filteredNotices: [SharedNoticeItem] {
        guard noticeSource != NoticeFilterOptions.allSourcesOption else {
            return notices
        }
        return notices.filter { $0.source == noticeSource }
    }

    var availableGradeFilters: [String] {
        GradeDisplayOptions.filters
    }

    var availableGradeSorts: [String] {
        GradeDisplayOptions.sorts
    }

    var filteredGrades: [SharedGradeItem] {
        guard let grades else { return [] }
        let filtered = grades.grades.filter { grade in
            switch gradeFilter {
            case GradeDisplayOptions.needsAttentionOption:
                return gradeNeedsAttention(grade)
            default:
                return true
            }
        }
        switch gradeSort {
        case GradeDisplayOptions.gradePointSortOption:
            return filtered.sorted {
                if $0.gradePoint == $1.gradePoint {
                    return $0.courseName.localizedStandardCompare($1.courseName) == .orderedAscending
                }
                return $0.gradePoint > $1.gradePoint
            }
        case GradeDisplayOptions.creditSortOption:
            return filtered.sorted {
                if $0.credit == $1.credit {
                    return $0.courseName.localizedStandardCompare($1.courseName) == .orderedAscending
                }
                return $0.credit > $1.credit
            }
        case GradeDisplayOptions.courseNameSortOption:
            return filtered.sorted {
                $0.courseName.localizedStandardCompare($1.courseName) == .orderedAscending
            }
        default:
            return filtered
        }
    }

    var gradeNeedsAttentionCount: Int {
        grades?.grades.filter(gradeNeedsAttention).count ?? 0
    }

    var highestGradePoint: Double? {
        grades?.grades.map(\.gradePoint).max()
    }

    func loadDashboard(force: Bool = false) async {
        guard force || dashboard == nil else { return }
        guard shouldStartLoad(.dashboard, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.dashboard) {
            dashboard = try await provider.dashboard(dayOfWeek: currentWeekday())
        }
    }

    func loadSchedule(force: Bool = false) async {
        guard force || schedule == nil else { return }
        guard shouldStartLoad(.schedule, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.schedule) {
            schedule = try await provider.schedule()
        }
    }

    func updateScheduleDayFilter(_ filter: String) {
        guard scheduleDayFilter != filter, availableScheduleDayFilters.contains(filter) else {
            return
        }
        scheduleDayFilter = filter
    }

    func updateTextbookFilter(_ filter: String) {
        guard textbookFilter != filter, availableTextbookFilters.contains(filter) else {
            return
        }
        textbookFilter = filter
    }

    func loadGrades(force: Bool = false) async {
        guard force || grades == nil else { return }
        guard shouldStartLoad(.grades, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.grades) {
            grades = try await provider.grades()
        }
    }

    func updateGradeFilter(_ filter: String) {
        guard gradeFilter != filter, availableGradeFilters.contains(filter) else {
            return
        }
        gradeFilter = filter
    }

    func updateGradeSort(_ sort: String) {
        guard gradeSort != sort, availableGradeSorts.contains(sort) else {
            return
        }
        gradeSort = sort
    }

    func loadCampusCard(force: Bool = false) async {
        guard force || campusCard == nil else { return }
        guard shouldStartLoad(.campusCard, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.campusCard) {
            let firstPage = try await provider.campusCard(page: 1, pageSize: campusCardPageSize)
            campusCard = firstPage
            campusCardPage = 1
            canLoadMoreCampusCardTransactions = firstPage.transactions.count >= campusCardPageSize
        }
    }

    func loadMoreCampusCardTransactions() async {
        guard let current = campusCard else {
            await loadCampusCard()
            return
        }
        guard canLoadMoreCampusCardTransactions, !isLoading(.campusCard) else {
            return
        }
        let nextPage = max(1, campusCardPage + 1)
        await load(.campusCard) {
            let next = try await provider.campusCard(page: nextPage, pageSize: campusCardPageSize)
            campusCard = SharedCampusCardSnapshot(
                info: next.info,
                transactions: mergedCampusCardTransactions(current.transactions, next.transactions)
            )
            campusCardPage = nextPage
            canLoadMoreCampusCardTransactions = next.transactions.count >= campusCardPageSize
        }
    }

    func loadNotices(force: Bool = false) async {
        guard force || !didLoadNotices else { return }
        guard shouldStartLoad(.notices, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.notices) {
            notices = try await provider.notices(page: 1)
            if !availableNoticeSources.contains(noticeSource) {
                noticeSource = NoticeFilterOptions.allSourcesOption
            }
            didLoadNotices = true
        }
    }

    func updateNoticeSource(_ source: String) {
        guard noticeSource != source, availableNoticeSources.contains(source) else {
            return
        }
        noticeSource = source
    }

    func loadEmptyRooms(force: Bool = false) async {
        guard force || !didLoadEmptyRooms else { return }
        guard shouldStartLoad(.emptyRooms, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.emptyRooms) {
            emptyRooms = try await provider.emptyRooms(
                campus: emptyRoomCampus,
                date: emptyRoomDateString(),
                sections: emptyRoomStartSection...emptyRoomEndSection
            )
            didLoadEmptyRooms = true
        }
    }

    func updateEmptyRoomCampus(_ campus: String) {
        guard emptyRoomCampus != campus, availableEmptyRoomCampuses.contains(campus) else {
            return
        }
        emptyRoomCampus = campus
        emptyRoomBuilding = EmptyRoomFilterOptions.allBuildingsOption
        invalidateEmptyRoomQuery()
    }

    func updateEmptyRoomBuilding(_ building: String) {
        guard emptyRoomBuilding != building, availableEmptyRoomBuildings.contains(building) else {
            return
        }
        emptyRoomBuilding = building
    }

    func updateEmptyRoomDate(_ date: Date) {
        let normalized = Calendar(identifier: .gregorian).startOfDay(for: date)
        guard !Calendar(identifier: .gregorian).isDate(emptyRoomDate, inSameDayAs: normalized) else {
            return
        }
        emptyRoomDate = normalized
        invalidateEmptyRoomQuery()
    }

    func updateEmptyRoomStartSection(_ section: Int) {
        let next = min(max(section, 1), 11)
        guard emptyRoomStartSection != next else {
            return
        }
        emptyRoomStartSection = next
        if emptyRoomEndSection < next {
            emptyRoomEndSection = next
        }
        invalidateEmptyRoomQuery()
    }

    func updateEmptyRoomEndSection(_ section: Int) {
        let next = min(max(section, 1), 11)
        guard emptyRoomEndSection != next else {
            return
        }
        emptyRoomEndSection = next
        if emptyRoomStartSection > next {
            emptyRoomStartSection = next
        }
        invalidateEmptyRoomQuery()
    }

    func clearCachedData() {
        if let cache = provider as? FeatureCacheClearing {
            cache.clearCachedFeatures()
        }
        autoAttemptedFeatures = []
        dashboard = nil
        schedule = nil
        scheduleDayFilter = ScheduleDisplayOptions.allDaysOption
        textbookFilter = TextbookDisplayOptions.allTextbooksOption
        grades = nil
        gradeFilter = GradeDisplayOptions.allGradesOption
        gradeSort = GradeDisplayOptions.defaultSortOption
        campusCard = nil
        campusCardPage = 0
        canLoadMoreCampusCardTransactions = false
        notices = []
        noticeSource = NoticeFilterOptions.allSourcesOption
        emptyRooms = []
        didLoadNotices = false
        didLoadEmptyRooms = false
        loadingFeatures = []
        failedFeatureMessages = [:]
        errorMessage = nil
        didClearCache = true
    }

    func acknowledgeCacheClear() {
        didClearCache = false
    }

    func retry(_ feature: FeatureRequestKind) async {
        switch feature {
        case .dashboard:
            await loadDashboard(force: true)
        case .schedule:
            await loadSchedule(force: true)
        case .grades:
            await loadGrades(force: true)
        case .campusCard:
            await loadCampusCard(force: true)
        case .notices:
            await loadNotices(force: true)
        case .emptyRooms:
            await loadEmptyRooms(force: true)
        }
    }

    func prepareForAuthenticatedSession() {
        autoAttemptedFeatures = []
        errorMessage = nil
    }

    func resumeAfterSiteVerification(siteName: String, selectedTab: AppTab) async {
        prepareForAuthenticatedSession()
        switch selectedTab {
        case .home:
            await loadDashboard(force: true)
        case .schedule:
            if siteName.isScheduleLike {
                await loadSchedule(force: true)
            }
        case .tools:
            if siteName == "campus_card" {
                await loadCampusCard(force: true)
            } else if siteName.isGradeLike {
                await loadGrades(force: true)
            }
        case .profile:
            break
        }
    }

    func isLoading(_ feature: FeatureRequestKind) -> Bool {
        loadingFeatures.contains(feature)
    }

    func errorMessage(for feature: FeatureRequestKind) -> String? {
        failedFeatureMessages[feature]
    }

    private func load(_ feature: FeatureRequestKind, operation: () async throws -> Void) async {
        await waitForExclusiveRealLoadIfNeeded()
        activeLoadCount += 1
        loadingFeatures.insert(feature)
        isLoading = true
        defer {
            loadingFeatures.remove(feature)
            activeLoadCount -= 1
            isLoading = activeLoadCount > 0
        }
        errorMessage = nil
        failedFeatureMessages[feature] = nil
        do {
            try await operation()
            _ = await syncAuthState?()
        } catch {
            let authState = await syncAuthState?()
            if case .siteVerificationRequired = authState {
                errorMessage = nil
                return
            }
            let message = "数据加载失败：\(error.localizedDescription)"
            failedFeatureMessages[feature] = message
            errorMessage = message
        }
    }

    private func waitForExclusiveRealLoadIfNeeded() async {
        guard XjtuLaunchArguments.dependencyMode == .realFirstReleaseCore else { return }
        while activeLoadCount > 0 {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
    }

    private func shouldStartLoad(_ feature: FeatureRequestKind, force: Bool) -> Bool {
        guard !force else { return true }
        guard !autoAttemptedFeatures.contains(feature) else { return false }
        autoAttemptedFeatures.insert(feature)
        return true
    }

    private func clearProviderCacheIfForced(_ force: Bool) {
        guard force, let cache = provider as? FeatureCacheClearing else {
            return
        }
        let now = Date()
        if let lastProviderCacheClearAt,
           now.timeIntervalSince(lastProviderCacheClearAt) < forcedRefreshCacheClearCooldown {
            return
        }
        cache.clearCachedFeatures()
        lastProviderCacheClearAt = now
    }

    private func currentWeekday() -> Int {
        let weekday = Calendar(identifier: .gregorian).component(.weekday, from: Date())
        return weekday == 1 ? 7 : weekday - 1
    }

    private func scheduleCoursePrecedes(_ lhs: SharedCourseItem, _ rhs: SharedCourseItem) -> Bool {
        if lhs.dayOfWeek != rhs.dayOfWeek {
            return lhs.dayOfWeek < rhs.dayOfWeek
        }
        if lhs.startSection != rhs.startSection {
            return lhs.startSection < rhs.startSection
        }
        if lhs.endSection != rhs.endSection {
            return lhs.endSection < rhs.endSection
        }
        return lhs.name.localizedStandardCompare(rhs.name) == .orderedAscending
    }

    private func todayString() -> String {
        formatDate(Date())
    }

    private func emptyRoomDateString() -> String {
        formatDate(emptyRoomDate)
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private func invalidateEmptyRoomQuery() {
        let previousError = failedFeatureMessages[.emptyRooms]
        emptyRooms = []
        didLoadEmptyRooms = false
        failedFeatureMessages[.emptyRooms] = nil
        autoAttemptedFeatures.remove(.emptyRooms)
        if errorMessage == previousError {
            errorMessage = nil
        }
    }

    private func mergedCampusCardTransactions(
        _ current: [SharedCampusCardTransaction],
        _ next: [SharedCampusCardTransaction]
    ) -> [SharedCampusCardTransaction] {
        var seenIds = Set(current.map(\.id))
        var merged = current
        for transaction in next where seenIds.insert(transaction.id).inserted {
            merged.append(transaction)
        }
        return merged
    }

    private func gradeNeedsAttention(_ grade: SharedGradeItem) -> Bool {
        if grade.gradePoint <= 0 {
            return true
        }
        let normalizedScore = grade.score.trimmingCharacters(in: .whitespacesAndNewlines)
        if let numericScore = Double(normalizedScore) {
            return numericScore < 60
        }
        return normalizedScore.contains("不及格") ||
            normalizedScore.contains("不通过") ||
            normalizedScore.localizedCaseInsensitiveContains("fail")
    }
}

private enum ScheduleDisplayOptions {
    static let allDaysOption = "全部课程"
    static let dayFilters = [
        allDaysOption,
        "周一",
        "周二",
        "周三",
        "周四",
        "周五",
        "周六",
        "周日"
    ]

    static func dayOfWeek(for filter: String) -> Int? {
        guard filter != allDaysOption else {
            return nil
        }
        return dayFilters.firstIndex(of: filter)
    }
}

private enum TextbookDisplayOptions {
    static let allTextbooksOption = "全部教材"
    static let substantiveTextbooksOption = "有教材"
    static let noTextbookOption = "无教材"

    static let filters = [
        allTextbooksOption,
        substantiveTextbooksOption,
        noTextbookOption
    ]
}

private enum GradeDisplayOptions {
    static let allGradesOption = "全部成绩"
    static let needsAttentionOption = "需关注"
    static let defaultSortOption = "原始顺序"
    static let gradePointSortOption = "绩点优先"
    static let creditSortOption = "学分优先"
    static let courseNameSortOption = "课程名"

    static let filters = [
        allGradesOption,
        needsAttentionOption
    ]

    static let sorts = [
        defaultSortOption,
        gradePointSortOption,
        creditSortOption,
        courseNameSortOption
    ]
}

private enum NoticeFilterOptions {
    static let allSourcesOption = "全部来源"
}

private enum EmptyRoomFilterOptions {
    static let allBuildingsOption = "全部教学楼"
    static let defaultCampus = "兴庆校区"

    static let campuses = [
        "兴庆校区",
        "雁塔校区",
        "曲江校区",
        "创新港校区",
        "苏州校区"
    ]

    static let buildingsByCampus: [String: [String]] = [
        "兴庆校区": [
            "主楼A", "主楼B", "主楼C", "主楼D", "中2", "中3",
            "西2东", "西2西", "外文楼A", "外文楼B", "东1东", "东2",
            "仲英楼", "东1西", "教2楼", "中1", "主楼E座",
            "工程馆", "工程坊A区", "文管", "计教中心", "田家炳"
        ],
        "雁塔校区": [
            "东配楼", "微免楼", "综合楼", "教学楼", "药学楼", "解剖楼",
            "生化楼", "病理楼", "西配楼", "一附院科教楼", "二院教学楼",
            "护理楼", "卫法楼"
        ],
        "曲江校区": ["西一楼", "西五楼", "西四楼", "西六楼"],
        "创新港校区": ["1", "2", "3", "4", "5", "9", "18", "19", "20", "21"],
        "苏州校区": ["公共学院5号楼"]
    ]
}

enum FeatureRequestKind: Hashable {
    case dashboard
    case schedule
    case grades
    case campusCard
    case notices
    case emptyRooms
}

private extension String {
    var isScheduleLike: Bool {
        self == "schedule" || self == "jwapp" || self == "jwxt"
    }

    var isGradeLike: Bool {
        self == "grade" || self == "jwapp" || self == "jwxt"
    }
}
