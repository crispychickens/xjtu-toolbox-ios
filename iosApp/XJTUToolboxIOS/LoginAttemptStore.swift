import Foundation

struct SavedLoginAttempt: Codable, Equatable {
    let transientFailureCount: Int
    let retryAtMillis: Int64?
}

protocol LoginAttemptStoring {
    func load(key: String) -> SavedLoginAttempt?
    func save(_ attempt: SavedLoginAttempt, key: String)
    func remove(key: String)
    func clear()
}

final class UserDefaultsLoginAttemptStore: LoginAttemptStoring {
    private let defaults: UserDefaults
    private let keyPrefix: String

    init(
        defaults: UserDefaults = .standard,
        keyPrefix: String = "com.xjtu.toolbox.ios.loginAttempt."
    ) {
        self.defaults = defaults
        self.keyPrefix = keyPrefix
    }

    func load(key: String) -> SavedLoginAttempt? {
        guard let data = defaults.data(forKey: storageKey(key)) else {
            return nil
        }
        return try? JSONDecoder().decode(SavedLoginAttempt.self, from: data)
    }

    func save(_ attempt: SavedLoginAttempt, key: String) {
        guard let data = try? JSONEncoder().encode(attempt) else {
            return
        }
        defaults.set(data, forKey: storageKey(key))
    }

    func remove(key: String) {
        defaults.removeObject(forKey: storageKey(key))
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
