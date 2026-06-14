import XCTest
@testable import XJTUToolboxIOS

final class FeatureCacheStoreTests: XCTestCase {
    func testFreshScheduleCacheAvoidsSecondProviderRequest() async throws {
        let provider = FeatureProviderSpy()
        let cache = InMemoryFeatureCacheStore()
        var now = Date(timeIntervalSince1970: 1_000)
        let subject = CachedFeatureProvider(
            provider: provider,
            cacheStore: cache,
            ttl: 300,
            now: { now }
        )

        let first = try await subject.schedule()
        now = now.addingTimeInterval(60)
        let second = try await subject.schedule()

        XCTAssertEqual(first, second)
        XCTAssertEqual(provider.scheduleRequests, 1)
        XCTAssertEqual(cache.savedKeys, ["schedule"])
    }

    func testStaleScheduleCacheFallsBackWhenRefreshFails() async throws {
        let provider = FeatureProviderSpy()
        let cache = InMemoryFeatureCacheStore()
        var now = Date(timeIntervalSince1970: 1_000)
        let subject = CachedFeatureProvider(
            provider: provider,
            cacheStore: cache,
            ttl: 300,
            now: { now }
        )

        let initial = try await subject.schedule()
        now = now.addingTimeInterval(301)
        provider.scheduleError = TestFailure()

        let fallback = try await subject.schedule()

        XCTAssertEqual(fallback, initial)
        XCTAssertEqual(provider.scheduleRequests, 2)
    }

    func testCachePolicyCanKeepSensitiveGradesOutOfPersistentStore() async throws {
        let provider = FeatureProviderSpy()
        let cache = InMemoryFeatureCacheStore()
        let subject = CachedFeatureProvider(
            provider: provider,
            cacheStore: cache,
            shouldCache: { $0 == "schedule" }
        )

        _ = try await subject.grades()
        _ = try await subject.grades()

        XCTAssertEqual(provider.gradeRequests, 2)
        XCTAssertTrue(cache.savedKeys.isEmpty)
    }

    func testClearingCacheCascadesToWrappedProvider() async throws {
        let provider = FeatureProviderSpy()
        let cache = InMemoryFeatureCacheStore()
        let subject = CachedFeatureProvider(provider: provider, cacheStore: cache)
        _ = try await subject.schedule()

        subject.clearCachedFeatures()

        XCTAssertTrue(cache.didClear)
        XCTAssertEqual(provider.clearRequests, 1)
    }
}

private struct TestFailure: Error {}

private final class InMemoryFeatureCacheStore: FeatureCacheStoring {
    private var entries: [String: Any] = [:]
    private(set) var savedKeys: [String] = []
    private(set) var didClear = false

    func load<Value: Codable>(_ type: Value.Type, forKey key: String) -> SavedFeatureCacheEntry<Value>? {
        entries[key] as? SavedFeatureCacheEntry<Value>
    }

    func save<Value: Codable>(_ entry: SavedFeatureCacheEntry<Value>, forKey key: String) {
        entries[key] = entry
        savedKeys.append(key)
    }

    func clear() {
        entries.removeAll()
        didClear = true
    }
}

private final class FeatureProviderSpy: SharedFeatureProviding, FeatureCacheClearing {
    private(set) var scheduleRequests = 0
    private(set) var gradeRequests = 0
    private(set) var clearRequests = 0
    var scheduleError: Error?

    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        SharedDashboardSnapshot(todayCourses: [], campusCard: nil, notices: [])
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        scheduleRequests += 1
        if let scheduleError {
            throw scheduleError
        }
        return SharedScheduleSnapshot(courses: [], exams: [], textbooks: [])
    }

    func grades() async throws -> SharedGradeSnapshot {
        gradeRequests += 1
        return SharedGradeSnapshot(grades: [], weightedGpa: nil, totalCredits: 0)
    }

    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail {
        throw TestFailure()
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        throw TestFailure()
    }

    func noticePage(page: Int) async throws -> SharedNoticePage {
        SharedNoticePage(total: 0, records: [])
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        []
    }

    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot {
        throw TestFailure()
    }

    func bookLibrarySeat(seatId: String, areaCode: String, allowSwap: Bool) async throws -> SharedLibrarySeatBookingResult {
        throw TestFailure()
    }

    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage {
        SharedCouponPage(filter: filter, total: 0, records: [])
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
        SharedSchoolCoursePage(termCode: termCode ?? "", total: 0, page: page, pageSize: pageSize, records: [])
    }

    func clearCachedFeatures() {
        clearRequests += 1
    }
}
