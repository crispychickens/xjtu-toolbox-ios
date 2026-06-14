import Foundation

@MainActor
final class FeatureStore: ObservableObject {
    @Published private(set) var dashboard: SharedDashboardSnapshot?
    @Published private(set) var schedule: SharedScheduleSnapshot?
    @Published private(set) var scheduleDayFilter = ScheduleDisplayOptions.allDaysOption
    @Published private(set) var scheduleWeekFilter: Int?
    @Published private(set) var textbookFilter = TextbookDisplayOptions.allTextbooksOption
    @Published private(set) var grades: SharedGradeSnapshot?
    @Published private(set) var gradeDetails: [String: SharedGradeDetail] = [:]
    @Published private(set) var loadingGradeDetailIDs: Set<String> = []
    @Published private(set) var gradeDetailErrors: [String: String] = [:]
    @Published private(set) var gradeTerm = GradeDisplayOptions.allTermsOption
    @Published private(set) var gradeFilter = GradeDisplayOptions.allGradesOption
    @Published private(set) var gradeSort = GradeDisplayOptions.defaultSortOption
    @Published private(set) var campusCard: SharedCampusCardSnapshot?
    @Published private(set) var campusCardPage = 0
    @Published private(set) var canLoadMoreCampusCardTransactions = false
    @Published private(set) var notices: [SharedNoticeItem] = []
    @Published private(set) var totalNotices = 0
    @Published private(set) var noticePage = 0
    @Published private(set) var canLoadMoreNotices = false
    @Published private(set) var noticeSource = NoticeFilterOptions.allSourcesOption
    @Published private(set) var emptyRooms: [SharedEmptyRoom] = []
    @Published private(set) var emptyRoomCampus = EmptyRoomFilterOptions.defaultCampus
    @Published private(set) var emptyRoomBuilding = EmptyRoomFilterOptions.allBuildingsOption
    @Published private(set) var emptyRoomDate = Date()
    @Published private(set) var emptyRoomStartSection = 1
    @Published private(set) var emptyRoomEndSection = 4
    @Published private(set) var emptyRoomMinimumCapacity = 0
    @Published private(set) var librarySeats: SharedLibrarySeatSnapshot?
    @Published private(set) var librarySeatAreaCode: String?
    @Published private(set) var libraryBookingResult: SharedLibrarySeatBookingResult?
    @Published private(set) var isBookingLibrarySeat = false
    @Published private(set) var coupons: [SharedCouponRecord] = []
    @Published private(set) var totalCoupons = 0
    @Published private(set) var couponPage = 0
    @Published private(set) var canLoadMoreCoupons = false
    @Published private(set) var couponFilter: SharedCouponFilter = .usable
    @Published private(set) var schoolCourses: [SharedSchoolCourse] = []
    @Published private(set) var schoolCourseTermCode = ""
    @Published private(set) var totalSchoolCourses = 0
    @Published private(set) var schoolCoursePage = 0
    @Published private(set) var canLoadMoreSchoolCourses = false
    @Published private(set) var schoolCourseNameQuery = ""
    @Published private(set) var schoolCourseTeacherQuery = ""
    @Published private(set) var schoolCourseCampusCode = ""
    @Published private(set) var schoolCourseWeekday = 0
    @Published private(set) var didSearchSchoolCourses = false
    @Published private(set) var didLoadNotices = false
    @Published private(set) var didLoadEmptyRooms = false
    @Published private(set) var didLoadLibrarySeats = false
    @Published private(set) var didLoadCoupons = false
    @Published private(set) var isLoading = false
    @Published private(set) var loadingFeatures: Set<FeatureRequestKind> = []
    @Published private(set) var failedFeatureMessages: [FeatureRequestKind: String] = [:]
    @Published var errorMessage: String?
    @Published private(set) var didClearCache = false

    private let provider: SharedFeatureProviding
    private let syncAuthState: (() async -> SharedAuthState)?
    private let campusCardPageSize = 20
    private let couponPageSize = 20
    private let schoolCoursePageSize = 20
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
        emptyRooms.filter { room in
            let matchesBuilding = emptyRoomBuilding == EmptyRoomFilterOptions.allBuildingsOption ||
                room.building == emptyRoomBuilding
            let matchesCapacity = emptyRoomMinimumCapacity == 0 || room.capacity >= emptyRoomMinimumCapacity
            return matchesBuilding && matchesCapacity
        }.sorted {
            if $0.building != $1.building {
                return $0.building.localizedStandardCompare($1.building) == .orderedAscending
            }
            if $0.capacity != $1.capacity {
                return $0.capacity > $1.capacity
            }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    var availableEmptyRoomMinimumCapacities: [Int] {
        EmptyRoomFilterOptions.minimumCapacities
    }

    var availableLibrarySeatAreas: [SharedLibraryAreaStats] {
        librarySeats?.areas ?? []
    }

    var selectedLibrarySeatArea: SharedLibraryAreaStats? {
        guard let librarySeats else { return nil }
        return librarySeats.areas.first { $0.code == librarySeats.selectedAreaCode } ?? librarySeats.areas.first
    }

    var availableLibrarySeats: [SharedLibrarySeatItem] {
        librarySeats?.seats.filter(\.available) ?? []
    }

    var availableCouponFilters: [SharedCouponFilter] {
        SharedCouponFilter.allCases
    }

    var loadedCouponValueTotal: Double {
        coupons.reduce(0) { $0 + max($1.leftAmountYuan, 0) }
    }

    var availableSchoolCourseCampuses: [SchoolCourseCampusOption] {
        SchoolCourseFilterOptions.campuses
    }

    var availableSchoolCourseWeekdays: [Int] {
        Array(0...7)
    }

    func schoolCourseWeekdayTitle(_ weekday: Int) -> String {
        SchoolCourseFilterOptions.weekdayTitle(weekday)
    }

    var availableScheduleDayFilters: [String] {
        ScheduleDisplayOptions.dayFilters
    }

    var scheduleShowsAllDays: Bool {
        scheduleDayFilter == ScheduleDisplayOptions.allDaysOption
    }

    var availableScheduleWeekFilters: [Int] {
        guard let schedule else { return [] }
        return Array(Set(schedule.courses.flatMap(\.weeks).filter { $0 > 0 })).sorted()
    }

    var scheduleWeekFilterLabel: String {
        guard let scheduleWeekFilter else {
            return "全部周次"
        }
        return "第 \(scheduleWeekFilter) 周"
    }

    var filteredScheduleCourses: [SharedCourseItem] {
        guard let schedule else { return [] }
        let weekFilteredCourses: [SharedCourseItem]
        if let scheduleWeekFilter {
            weekFilteredCourses = schedule.courses.filter { $0.weeks.contains(scheduleWeekFilter) }
        } else {
            weekFilteredCourses = schedule.courses
        }
        let dayFilteredCourses: [SharedCourseItem]
        if let selectedDay = ScheduleDisplayOptions.dayOfWeek(for: scheduleDayFilter) {
            dayFilteredCourses = weekFilteredCourses.filter { $0.dayOfWeek == selectedDay }
        } else {
            dayFilteredCourses = weekFilteredCourses
        }
        return dayFilteredCourses.sorted(by: scheduleCoursePrecedes)
    }

    var sortedScheduleExams: [SharedExamItem] {
        schedule?.exams.sorted {
            let lhsTime = $0.examDate.isEmpty ? $0.time : "\($0.examDate) \($0.examTime)"
            let rhsTime = $1.examDate.isEmpty ? $1.time : "\($1.examDate) \($1.examTime)"
            if lhsTime != rhsTime {
                return lhsTime.localizedStandardCompare(rhsTime) == .orderedAscending
            }
            return $0.courseName.localizedStandardCompare($1.courseName) == .orderedAscending
        } ?? []
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

    var noticeShowsAllSources: Bool {
        noticeSource == NoticeFilterOptions.allSourcesOption
    }

    var availableGradeFilters: [String] {
        GradeDisplayOptions.filters
    }

    var availableGradeSorts: [String] {
        GradeDisplayOptions.sorts
    }

    var availableGradeTerms: [String] {
        let terms = Set(grades?.grades.map(\.termCode).filter { !$0.isEmpty } ?? [])
            .sorted { $0.localizedStandardCompare($1) == .orderedDescending }
        return [GradeDisplayOptions.allTermsOption] + terms
    }

    var shouldShowGradeTermPicker: Bool {
        let knownTerms = availableGradeTerms.count - 1
        let hasUnknownTerms = grades?.grades.contains { $0.termCode.isEmpty } ?? false
        return knownTerms > 1 || (knownTerms == 1 && hasUnknownTerms)
    }

    var gradeScopeGrades: [SharedGradeItem] {
        guard let grades else { return [] }
        guard gradeTerm != GradeDisplayOptions.allTermsOption else {
            return grades.grades
        }
        return grades.grades.filter { $0.termCode == gradeTerm }
    }

    var filteredGrades: [SharedGradeItem] {
        let filtered = gradeScopeGrades.filter { grade in
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
        gradeScopeGrades.filter(gradeNeedsAttention).count
    }

    var highestGradePoint: Double? {
        gradeScopeGrades.map(\.gradePoint).max()
    }

    var gradeScopeTotalCredits: Double {
        gradeScopeGrades.reduce(0) { $0 + $1.credit }
    }

    var gradeScopeWeightedGpa: Double? {
        let credits = gradeScopeTotalCredits
        guard credits > 0 else { return nil }
        return gradeScopeGrades.reduce(0) { $0 + $1.gradePoint * $1.credit } / credits
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
            if let scheduleWeekFilter, !availableScheduleWeekFilters.contains(scheduleWeekFilter) {
                self.scheduleWeekFilter = nil
            }
        }
    }

    func updateScheduleDayFilter(_ filter: String) {
        guard scheduleDayFilter != filter, availableScheduleDayFilters.contains(filter) else {
            return
        }
        scheduleDayFilter = filter
    }

    func updateScheduleWeekFilter(_ week: Int?) {
        guard scheduleWeekFilter != week else {
            return
        }
        guard let week else {
            scheduleWeekFilter = nil
            return
        }
        guard availableScheduleWeekFilters.contains(week) else {
            return
        }
        scheduleWeekFilter = week
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
        let shouldPreferLatestTerm = grades == nil
        clearProviderCacheIfForced(force)
        if force {
            gradeDetails = [:]
            gradeDetailErrors = [:]
        }
        await load(.grades) {
            grades = try await provider.grades()
            reconcileGradeTermSelection(prefersLatest: shouldPreferLatestTerm)
        }
    }

    func updateGradeTerm(_ term: String) {
        guard gradeTerm != term, availableGradeTerms.contains(term) else {
            return
        }
        gradeTerm = term
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

    func loadGradeDetail(for grade: SharedGradeItem, force: Bool = false) async {
        guard !grade.id.isEmpty else { return }
        guard force || gradeDetails[grade.id] == nil else { return }
        guard !loadingGradeDetailIDs.contains(grade.id) else { return }
        await waitForExclusiveRealLoadIfNeeded()
        activeLoadCount += 1
        loadingGradeDetailIDs.insert(grade.id)
        isLoading = true
        gradeDetailErrors[grade.id] = nil
        defer {
            loadingGradeDetailIDs.remove(grade.id)
            activeLoadCount -= 1
            isLoading = activeLoadCount > 0
        }
        do {
            gradeDetails[grade.id] = try await provider.gradeDetail(gradeId: grade.id)
            _ = await syncAuthState?()
        } catch {
            let authState = await syncAuthState?()
            if case .siteVerificationRequired = authState {
                return
            }
            gradeDetailErrors[grade.id] = "成绩详情加载失败：\(error.localizedDescription)"
        }
    }

    func gradeDetail(for grade: SharedGradeItem) -> SharedGradeDetail? {
        gradeDetails[grade.id]
    }

    func isLoadingGradeDetail(for grade: SharedGradeItem) -> Bool {
        loadingGradeDetailIDs.contains(grade.id)
    }

    func gradeDetailError(for grade: SharedGradeItem) -> String? {
        gradeDetailErrors[grade.id]
    }

    func loadCampusCard(force: Bool = false) async {
        guard force || campusCard == nil else { return }
        guard shouldStartLoad(.campusCard, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.campusCard) {
            let firstPage = try await provider.campusCard(page: 1, pageSize: campusCardPageSize)
            campusCard = firstPage
            campusCardPage = 1
            canLoadMoreCampusCardTransactions = firstPage.transactions.count < firstPage.totalTransactions
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
                transactions: mergedCampusCardTransactions(current.transactions, next.transactions),
                totalTransactions: max(current.totalTransactions, next.totalTransactions)
            )
            campusCardPage = nextPage
            canLoadMoreCampusCardTransactions =
                (campusCard?.transactions.count ?? 0) < (campusCard?.totalTransactions ?? 0)
        }
    }

    func loadNotices(force: Bool = false) async {
        guard force || !didLoadNotices else { return }
        guard shouldStartLoad(.notices, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.notices) {
            let firstPage = try await provider.noticePage(page: 1)
            notices = firstPage.records
            totalNotices = firstPage.total
            noticePage = 1
            canLoadMoreNotices = notices.count < totalNotices
            if !availableNoticeSources.contains(noticeSource) {
                noticeSource = NoticeFilterOptions.allSourcesOption
            }
            didLoadNotices = true
        }
    }

    func loadMoreNotices() async {
        guard didLoadNotices else {
            await loadNotices()
            return
        }
        guard canLoadMoreNotices, !isLoading(.notices) else {
            return
        }
        let nextPage = max(1, noticePage + 1)
        await load(.notices) {
            let next = try await provider.noticePage(page: nextPage)
            notices = mergedNotices(notices, next.records)
            totalNotices = max(totalNotices, next.total)
            noticePage = nextPage
            canLoadMoreNotices = notices.count < totalNotices
            if !availableNoticeSources.contains(noticeSource) {
                noticeSource = NoticeFilterOptions.allSourcesOption
            }
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

    func updateEmptyRoomMinimumCapacity(_ capacity: Int) {
        guard emptyRoomMinimumCapacity != capacity,
              availableEmptyRoomMinimumCapacities.contains(capacity) else {
            return
        }
        emptyRoomMinimumCapacity = capacity
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

    func loadLibrarySeats(force: Bool = false) async {
        guard force || !didLoadLibrarySeats else { return }
        guard shouldStartLoad(.librarySeats, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.librarySeats) {
            let snapshot = try await provider.librarySeats(areaCode: librarySeatAreaCode)
            librarySeats = snapshot
            librarySeatAreaCode = snapshot.selectedAreaCode
            didLoadLibrarySeats = true
        }
    }

    func updateLibrarySeatArea(_ areaCode: String) {
        guard librarySeatAreaCode != areaCode else {
            return
        }
        librarySeatAreaCode = areaCode
        invalidateLibrarySeatQuery()
    }

    func bookLibrarySeat(seatId: String, allowSwap: Bool = true) async {
        let normalizedSeatId = seatId.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalizedSeatId.isEmpty,
              let areaCode = librarySeatAreaCode ?? librarySeats?.selectedAreaCode else {
            return
        }
        isBookingLibrarySeat = true
        libraryBookingResult = nil
        defer {
            isBookingLibrarySeat = false
        }
        do {
            let result = try await provider.bookLibrarySeat(
                seatId: normalizedSeatId,
                areaCode: areaCode,
                allowSwap: allowSwap
            )
            libraryBookingResult = result
            if result.success {
                invalidateLibrarySeatQuery()
                await loadLibrarySeats(force: true)
            }
        } catch {
            libraryBookingResult = SharedLibrarySeatBookingResult(
                success: false,
                message: error.localizedDescription,
                finalURL: ""
            )
        }
    }

    func loadCoupons(force: Bool = false) async {
        guard force || !didLoadCoupons else { return }
        guard shouldStartLoad(.coupons, force: force) else { return }
        clearProviderCacheIfForced(force)
        await load(.coupons) {
            let firstPage = try await provider.coupons(filter: couponFilter, page: 1, pageSize: couponPageSize)
            coupons = firstPage.records
            totalCoupons = firstPage.total
            couponPage = 1
            canLoadMoreCoupons = coupons.count < totalCoupons
            didLoadCoupons = true
        }
    }

    func loadMoreCoupons() async {
        guard didLoadCoupons else {
            await loadCoupons()
            return
        }
        guard canLoadMoreCoupons, !isLoading(.coupons) else {
            return
        }
        let nextPage = max(1, couponPage + 1)
        await load(.coupons) {
            let next = try await provider.coupons(filter: couponFilter, page: nextPage, pageSize: couponPageSize)
            coupons = mergedCoupons(coupons, next.records)
            totalCoupons = max(totalCoupons, next.total)
            couponPage = nextPage
            canLoadMoreCoupons = coupons.count < totalCoupons
        }
    }

    func updateCouponFilter(_ filter: SharedCouponFilter) {
        guard couponFilter != filter else {
            return
        }
        couponFilter = filter
        invalidateCouponQuery()
    }

    func updateSchoolCourseNameQuery(_ value: String) {
        guard schoolCourseNameQuery != value else { return }
        schoolCourseNameQuery = value
        invalidateSchoolCourseQuery()
    }

    func updateSchoolCourseTeacherQuery(_ value: String) {
        guard schoolCourseTeacherQuery != value else { return }
        schoolCourseTeacherQuery = value
        invalidateSchoolCourseQuery()
    }

    func updateSchoolCourseCampusCode(_ code: String) {
        guard schoolCourseCampusCode != code,
              availableSchoolCourseCampuses.contains(where: { $0.code == code }) else {
            return
        }
        schoolCourseCampusCode = code
        invalidateSchoolCourseQuery()
    }

    func updateSchoolCourseWeekday(_ weekday: Int) {
        guard schoolCourseWeekday != weekday, availableSchoolCourseWeekdays.contains(weekday) else {
            return
        }
        schoolCourseWeekday = weekday
        invalidateSchoolCourseQuery()
    }

    func searchSchoolCourses() async {
        guard !isLoading(.schoolCourses) else { return }
        didSearchSchoolCourses = true
        await load(.schoolCourses) {
            let firstPage = try await provider.schoolCourses(
                termCode: schoolCourseTermCode.isEmpty ? nil : schoolCourseTermCode,
                courseName: schoolCourseNameQuery,
                teacher: schoolCourseTeacherQuery,
                campusCode: schoolCourseCampusCode,
                weekday: schoolCourseWeekday,
                page: 1,
                pageSize: schoolCoursePageSize
            )
            schoolCourses = firstPage.records
            schoolCourseTermCode = firstPage.termCode
            totalSchoolCourses = firstPage.total
            schoolCoursePage = 1
            canLoadMoreSchoolCourses = schoolCourses.count < totalSchoolCourses
        }
    }

    func loadMoreSchoolCourses() async {
        guard didSearchSchoolCourses else {
            await searchSchoolCourses()
            return
        }
        guard canLoadMoreSchoolCourses, !isLoading(.schoolCourses) else { return }
        let nextPage = max(1, schoolCoursePage + 1)
        await load(.schoolCourses) {
            let next = try await provider.schoolCourses(
                termCode: schoolCourseTermCode.isEmpty ? nil : schoolCourseTermCode,
                courseName: schoolCourseNameQuery,
                teacher: schoolCourseTeacherQuery,
                campusCode: schoolCourseCampusCode,
                weekday: schoolCourseWeekday,
                page: nextPage,
                pageSize: schoolCoursePageSize
            )
            schoolCourses = mergedSchoolCourses(schoolCourses, next.records)
            schoolCourseTermCode = next.termCode
            totalSchoolCourses = max(totalSchoolCourses, next.total)
            schoolCoursePage = nextPage
            canLoadMoreSchoolCourses = schoolCourses.count < totalSchoolCourses
        }
    }

    func clearCachedData() {
        if let cache = provider as? FeatureCacheClearing {
            cache.clearCachedFeatures()
        }
        autoAttemptedFeatures = []
        dashboard = nil
        schedule = nil
        scheduleDayFilter = ScheduleDisplayOptions.allDaysOption
        scheduleWeekFilter = nil
        textbookFilter = TextbookDisplayOptions.allTextbooksOption
        grades = nil
        gradeDetails = [:]
        loadingGradeDetailIDs = []
        gradeDetailErrors = [:]
        gradeTerm = GradeDisplayOptions.allTermsOption
        gradeFilter = GradeDisplayOptions.allGradesOption
        gradeSort = GradeDisplayOptions.defaultSortOption
        campusCard = nil
        campusCardPage = 0
        canLoadMoreCampusCardTransactions = false
        notices = []
        totalNotices = 0
        noticePage = 0
        canLoadMoreNotices = false
        noticeSource = NoticeFilterOptions.allSourcesOption
        emptyRooms = []
        emptyRoomMinimumCapacity = 0
        librarySeats = nil
        librarySeatAreaCode = nil
        libraryBookingResult = nil
        isBookingLibrarySeat = false
        coupons = []
        totalCoupons = 0
        couponPage = 0
        canLoadMoreCoupons = false
        couponFilter = .usable
        schoolCourses = []
        schoolCourseTermCode = ""
        totalSchoolCourses = 0
        schoolCoursePage = 0
        canLoadMoreSchoolCourses = false
        schoolCourseNameQuery = ""
        schoolCourseTeacherQuery = ""
        schoolCourseCampusCode = ""
        schoolCourseWeekday = 0
        didSearchSchoolCourses = false
        didLoadNotices = false
        didLoadEmptyRooms = false
        didLoadLibrarySeats = false
        didLoadCoupons = false
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
        case .librarySeats:
            await loadLibrarySeats(force: true)
        case .coupons:
            await loadCoupons(force: true)
        case .schoolCourses:
            await searchSchoolCourses()
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
            } else if siteName.isLibraryLike {
                await loadLibrarySeats(force: true)
            } else if siteName.isCouponLike {
                await loadCoupons(force: true)
            } else if siteName.isScheduleLike, didSearchSchoolCourses {
                await searchSchoolCourses()
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
        guard !loadingFeatures.contains(feature) else { return false }
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

    private func invalidateLibrarySeatQuery() {
        let previousError = failedFeatureMessages[.librarySeats]
        librarySeats = nil
        didLoadLibrarySeats = false
        failedFeatureMessages[.librarySeats] = nil
        autoAttemptedFeatures.remove(.librarySeats)
        if errorMessage == previousError {
            errorMessage = nil
        }
    }

    private func invalidateCouponQuery() {
        let previousError = failedFeatureMessages[.coupons]
        coupons = []
        totalCoupons = 0
        couponPage = 0
        canLoadMoreCoupons = false
        didLoadCoupons = false
        failedFeatureMessages[.coupons] = nil
        autoAttemptedFeatures.remove(.coupons)
        if errorMessage == previousError {
            errorMessage = nil
        }
    }

    private func invalidateSchoolCourseQuery() {
        let previousError = failedFeatureMessages[.schoolCourses]
        schoolCourses = []
        schoolCourseTermCode = ""
        totalSchoolCourses = 0
        schoolCoursePage = 0
        canLoadMoreSchoolCourses = false
        didSearchSchoolCourses = false
        failedFeatureMessages[.schoolCourses] = nil
        autoAttemptedFeatures.remove(.schoolCourses)
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

    private func mergedNotices(
        _ current: [SharedNoticeItem],
        _ next: [SharedNoticeItem]
    ) -> [SharedNoticeItem] {
        var seenIds = Set(current.map(\.id))
        var merged = current
        for notice in next where seenIds.insert(notice.id).inserted {
            merged.append(notice)
        }
        return merged
    }

    private func mergedCoupons(
        _ current: [SharedCouponRecord],
        _ next: [SharedCouponRecord]
    ) -> [SharedCouponRecord] {
        var seenIds = Set(current.map(\.id))
        var merged = current
        for coupon in next where seenIds.insert(coupon.id).inserted {
            merged.append(coupon)
        }
        return merged
    }

    private func mergedSchoolCourses(
        _ current: [SharedSchoolCourse],
        _ next: [SharedSchoolCourse]
    ) -> [SharedSchoolCourse] {
        var seenIds = Set(current.map(\.id))
        var merged = current
        for course in next where seenIds.insert(course.id).inserted {
            merged.append(course)
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

    private func reconcileGradeTermSelection(prefersLatest: Bool) {
        let terms = availableGradeTerms.filter { $0 != GradeDisplayOptions.allTermsOption }
        guard let latestTerm = terms.first else {
            gradeTerm = GradeDisplayOptions.allTermsOption
            return
        }
        if prefersLatest || !availableGradeTerms.contains(gradeTerm) {
            gradeTerm = latestTerm
        }
    }
}

private enum ScheduleDisplayOptions {
    static let allDaysOption = "全部星期"
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

enum GradeDisplayOptions {
    static let allTermsOption = "全部学期"
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
    static let minimumCapacities = [0, 30, 60, 100]

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
    case librarySeats
    case coupons
    case schoolCourses
}

struct SchoolCourseCampusOption: Identifiable, Equatable {
    let code: String
    let name: String

    var id: String { code }
}

private enum SchoolCourseFilterOptions {
    static let campuses = [
        SchoolCourseCampusOption(code: "", name: "不限校区"),
        SchoolCourseCampusOption(code: "1", name: "兴庆校区"),
        SchoolCourseCampusOption(code: "2", name: "雁塔校区"),
        SchoolCourseCampusOption(code: "3", name: "曲江校区"),
        SchoolCourseCampusOption(code: "4", name: "苏州校区"),
        SchoolCourseCampusOption(code: "5", name: "创新港校区")
    ]

    static func weekdayTitle(_ weekday: Int) -> String {
        switch weekday {
        case 1: return "周一"
        case 2: return "周二"
        case 3: return "周三"
        case 4: return "周四"
        case 5: return "周五"
        case 6: return "周六"
        case 7: return "周日"
        default: return "不限星期"
        }
    }
}

private extension String {
    var isScheduleLike: Bool {
        self == "schedule" || self == "jwapp" || self == "jwxt"
    }

    var isGradeLike: Bool {
        self == "grade" || self == "jwapp" || self == "jwxt"
    }

    var isLibraryLike: Bool {
        self == "library"
    }

    var isCouponLike: Bool {
        self == "coupon"
    }
}
