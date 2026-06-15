import XCTest
@testable import XJTUToolboxIOS

@MainActor
final class FeatureStoreTests: XCTestCase {
    func testClearingFeatureCacheDoesNotRemoveKeychainCredentials() throws {
        let service = "com.xjtu.toolbox.ios.tests.credentials.\(UUID().uuidString)"
        let credentialStore = KeychainCredentialStore(service: service)
        let credentials = SavedCredentials(username: "3124000000", password: "test-password")
        try credentialStore.save(credentials)
        defer { try? credentialStore.clear() }

        let provider = FeatureStoreProviderSpy()
        let subject = FeatureStore(provider: provider)

        subject.clearCachedData()

        XCTAssertEqual(try credentialStore.load(), credentials)
        XCTAssertEqual(provider.clearRequests, 1)
        XCTAssertTrue(subject.didClearCache)
    }
}

private struct TestFailure: Error {}

private final class FeatureStoreProviderSpy: SharedFeatureProviding, FeatureCacheClearing {
    private(set) var clearRequests = 0

    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        SharedDashboardSnapshot(todayCourses: [], campusCard: nil, notices: [])
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        SharedScheduleSnapshot(courses: [], exams: [], textbooks: [])
    }

    func grades() async throws -> SharedGradeSnapshot {
        SharedGradeSnapshot(grades: [], weightedGpa: nil, totalCredits: 0)
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
