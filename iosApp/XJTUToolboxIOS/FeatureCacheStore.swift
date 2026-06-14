import Foundation

struct SavedFeatureCacheEntry<Value: Codable>: Codable {
    let value: Value
    let storedAt: Date
}

protocol FeatureCacheStoring {
    func load<Value: Codable>(_ type: Value.Type, forKey key: String) -> SavedFeatureCacheEntry<Value>?
    func save<Value: Codable>(_ entry: SavedFeatureCacheEntry<Value>, forKey key: String)
    func clear()
}

final class UserDefaultsFeatureCacheStore: FeatureCacheStoring {
    private let defaults: UserDefaults
    private let keyPrefix: String

    init(
        defaults: UserDefaults = .standard,
        keyPrefix: String = "com.xjtu.toolbox.ios.featureCache."
    ) {
        self.defaults = defaults
        self.keyPrefix = keyPrefix
    }

    func load<Value: Codable>(_ type: Value.Type, forKey key: String) -> SavedFeatureCacheEntry<Value>? {
        guard let data = defaults.data(forKey: storageKey(key)) else {
            return nil
        }
        return try? JSONDecoder().decode(SavedFeatureCacheEntry<Value>.self, from: data)
    }

    func save<Value: Codable>(_ entry: SavedFeatureCacheEntry<Value>, forKey key: String) {
        guard let data = try? JSONEncoder().encode(entry) else {
            return
        }
        defaults.set(data, forKey: storageKey(key))
    }

    func clear() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(keyPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    private func storageKey(_ key: String) -> String {
        keyPrefix + key
    }
}

protocol FeatureCacheClearing {
    func clearCachedFeatures()
}

final class CachedFeatureProvider: SharedFeatureProviding, FeatureCacheClearing {
    private let provider: SharedFeatureProviding
    private let cacheStore: FeatureCacheStoring
    private let ttl: TimeInterval
    private let now: () -> Date
    private let shouldCache: (String) -> Bool

    init(
        provider: SharedFeatureProviding,
        cacheStore: FeatureCacheStoring = UserDefaultsFeatureCacheStore(),
        ttl: TimeInterval = 5 * 60,
        now: @escaping () -> Date = Date.init,
        shouldCache: @escaping (String) -> Bool = { _ in true }
    ) {
        self.provider = provider
        self.cacheStore = cacheStore
        self.ttl = ttl
        self.now = now
        self.shouldCache = shouldCache
    }

    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        try await cached(
            SharedDashboardSnapshot.self,
            key: "dashboard:\(dayOfWeek)",
            fetch: { try await provider.dashboard(dayOfWeek: dayOfWeek) }
        )
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        try await cached(
            SharedScheduleSnapshot.self,
            key: "schedule",
            fetch: { try await provider.schedule() }
        )
    }

    func grades() async throws -> SharedGradeSnapshot {
        try await cached(
            SharedGradeSnapshot.self,
            key: "grades",
            fetch: { try await provider.grades() }
        )
    }

    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail {
        try await provider.gradeDetail(gradeId: gradeId)
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        try await cached(
            SharedCampusCardSnapshot.self,
            key: "campusCard:\(page):\(pageSize)",
            fetch: { try await provider.campusCard(page: page, pageSize: pageSize) }
        )
    }

    func noticePage(page: Int) async throws -> SharedNoticePage {
        try await cached(
            SharedNoticePage.self,
            key: "notices:\(page)",
            fetch: { try await provider.noticePage(page: page) }
        )
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        try await cached(
            [SharedEmptyRoom].self,
            key: "emptyRooms:\(campus):\(date):\(sections.lowerBound):\(sections.upperBound)",
            fetch: { try await provider.emptyRooms(campus: campus, date: date, sections: sections) }
        )
    }

    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot {
        try await cached(
            SharedLibrarySeatSnapshot.self,
            key: "librarySeats:\(areaCode ?? "")",
            fetch: { try await provider.librarySeats(areaCode: areaCode) }
        )
    }

    func bookLibrarySeat(seatId: String, areaCode: String, allowSwap: Bool) async throws -> SharedLibrarySeatBookingResult {
        try await provider.bookLibrarySeat(seatId: seatId, areaCode: areaCode, allowSwap: allowSwap)
    }

    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage {
        try await provider.coupons(filter: filter, page: page, pageSize: pageSize)
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
        try await provider.schoolCourses(
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
        cacheStore.clear()
        (provider as? FeatureCacheClearing)?.clearCachedFeatures()
    }

    private func cached<Value: Codable>(
        _ type: Value.Type,
        key: String,
        fetch: () async throws -> Value
    ) async throws -> Value {
        guard shouldCache(key) else {
            return try await fetch()
        }

        let cached = cacheStore.load(type, forKey: key)
        if let cached, now().timeIntervalSince(cached.storedAt) < ttl {
            return cached.value
        }

        do {
            let value = try await fetch()
            cacheStore.save(SavedFeatureCacheEntry(value: value, storedAt: now()), forKey: key)
            return value
        } catch {
            if let cached {
                return cached.value
            }
            throw error
        }
    }
}
