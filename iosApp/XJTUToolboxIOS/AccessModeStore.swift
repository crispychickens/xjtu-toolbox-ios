import Foundation

protocol AccessModeStoring {
    func load() -> SharedAccessMode
    func save(_ mode: SharedAccessMode)
}

final class UserDefaultsAccessModeStore: AccessModeStoring {
    private let defaults: UserDefaults
    private let key: String

    init(
        defaults: UserDefaults = .standard,
        key: String = "com.xjtu.toolbox.ios.accessMode"
    ) {
        self.defaults = defaults
        self.key = key
    }

    func load() -> SharedAccessMode {
        guard
            let rawValue = defaults.string(forKey: key),
            let mode = SharedAccessMode(rawValue: rawValue)
        else {
            return .automatic
        }
        return mode
    }

    func save(_ mode: SharedAccessMode) {
        defaults.set(mode.rawValue, forKey: key)
    }
}
