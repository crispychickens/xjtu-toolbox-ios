#if DEBUG
import Foundation

enum PreviewRecoveryFeature: String, Equatable {
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

enum PreviewRecoveryScenario: Equatable {
    case empty(PreviewRecoveryFeature)
    case failOnce(PreviewRecoveryFeature)
}

final class PreviewRecoveryFeatureProvider: SharedFeatureProviding, FeatureCacheClearing {
    private let provider: SharedFeatureProviding
    private let scenario: PreviewRecoveryScenario
    private let failureState = PreviewRecoveryFailureState()

    init(provider: SharedFeatureProviding, scenario: PreviewRecoveryScenario) {
        self.provider = provider
        self.scenario = scenario
    }

    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        try await failOnceIfNeeded(.dashboard)
        guard !shouldReturnEmpty(.dashboard) else {
            return SharedDashboardSnapshot(todayCourses: [], campusCard: nil, notices: [])
        }
        return try await provider.dashboard(dayOfWeek: dayOfWeek)
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        try await failOnceIfNeeded(.schedule)
        guard !shouldReturnEmpty(.schedule) else {
            return SharedScheduleSnapshot(courses: [], exams: [], textbooks: [])
        }
        return try await provider.schedule()
    }

    func grades() async throws -> SharedGradeSnapshot {
        try await failOnceIfNeeded(.grades)
        guard !shouldReturnEmpty(.grades) else {
            return SharedGradeSnapshot(grades: [], weightedGpa: nil, totalCredits: 0)
        }
        return try await provider.grades()
    }

    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail {
        try await failOnceIfNeeded(.grades)
        return try await provider.gradeDetail(gradeId: gradeId)
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        try await failOnceIfNeeded(.campusCard)
        guard !shouldReturnEmpty(.campusCard) else {
            return SharedCampusCardSnapshot(
                info: SharedCampusCardInfo(balanceYuan: 0, holderName: "学生"),
                transactions: [],
                totalTransactions: 0
            )
        }
        return try await provider.campusCard(page: page, pageSize: pageSize)
    }

    func noticePage(page: Int) async throws -> SharedNoticePage {
        try await failOnceIfNeeded(.notices)
        guard !shouldReturnEmpty(.notices) else {
            return SharedNoticePage(total: 0, records: [])
        }
        return try await provider.noticePage(page: page)
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        try await failOnceIfNeeded(.emptyRooms)
        guard !shouldReturnEmpty(.emptyRooms) else {
            return []
        }
        return try await provider.emptyRooms(campus: campus, date: date, sections: sections)
    }

    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot {
        try await failOnceIfNeeded(.librarySeats)
        guard !shouldReturnEmpty(.librarySeats) else {
            return SharedLibrarySeatSnapshot(
                selectedAreaCode: areaCode ?? "",
                areas: [],
                seats: [],
                recommendedAreas: [],
                myBooking: nil
            )
        }
        return try await provider.librarySeats(areaCode: areaCode)
    }

    func bookLibrarySeat(
        seatId: String,
        areaCode: String,
        allowSwap: Bool
    ) async throws -> SharedLibrarySeatBookingResult {
        try await failOnceIfNeeded(.librarySeats)
        return try await provider.bookLibrarySeat(seatId: seatId, areaCode: areaCode, allowSwap: allowSwap)
    }

    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage {
        try await failOnceIfNeeded(.coupons)
        guard !shouldReturnEmpty(.coupons) else {
            return SharedCouponPage(filter: filter, total: 0, records: [])
        }
        return try await provider.coupons(filter: filter, page: page, pageSize: pageSize)
    }

    func schoolCourses(
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int
    ) async throws -> SharedSchoolCoursePage {
        try await failOnceIfNeeded(.schoolCourses)
        guard !shouldReturnEmpty(.schoolCourses) else {
            return SharedSchoolCoursePage(
                termCode: termCode ?? "",
                total: 0,
                page: page,
                pageSize: pageSize,
                records: []
            )
        }
        return try await provider.schoolCourses(
            termCode: termCode,
            courseName: courseName,
            teacher: teacher,
            campusCode: campusCode,
            weekday: weekday,
            page: page,
            pageSize: pageSize
        )
    }

    func clearCachedFeatures() {
        (provider as? FeatureCacheClearing)?.clearCachedFeatures()
    }

    private func shouldReturnEmpty(_ feature: PreviewRecoveryFeature) -> Bool {
        scenario == .empty(feature)
    }

    private func failOnceIfNeeded(_ feature: PreviewRecoveryFeature) async throws {
        guard scenario == .failOnce(feature) else { return }
        if await failureState.consumeFailureIfAvailable() {
            throw PreviewRecoveryError.firstLoadFailure
        }
    }
}

private actor PreviewRecoveryFailureState {
    private var didFail = false

    func consumeFailureIfAvailable() -> Bool {
        guard !didFail else { return false }
        didFail = true
        return true
    }
}

private enum PreviewRecoveryError: LocalizedError {
    case firstLoadFailure

    var errorDescription: String? {
        "预览恢复场景：首次加载失败"
    }
}
#endif
