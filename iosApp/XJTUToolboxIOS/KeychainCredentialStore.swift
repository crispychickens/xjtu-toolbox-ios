import Foundation
import Security

struct SavedCredentials: Equatable {
    let username: String
    let password: String
}

protocol CredentialStoring {
    func save(_ credentials: SavedCredentials) throws
    func load() throws -> SavedCredentials?
    func clear() throws
}

enum KeychainCredentialError: Error {
    case encodeFailed
    case decodeFailed
    case unexpectedStatus(OSStatus)
}

final class KeychainCredentialStore: CredentialStoring {
    private let service: String
    private let account: String

    init(
        service: String = "com.xjtu.toolbox.ios.credentials",
        account: String = "primary"
    ) {
        self.service = service
        self.account = account
    }

    func save(_ credentials: SavedCredentials) throws {
        let data = try encode(credentials)
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        if status == errSecDuplicateItem {
            let updateStatus = SecItemUpdate(
                baseQuery() as CFDictionary,
                [kSecValueData as String: data] as CFDictionary
            )
            guard updateStatus == errSecSuccess else {
                throw KeychainCredentialError.unexpectedStatus(updateStatus)
            }
            return
        }

        guard status == errSecSuccess else {
            throw KeychainCredentialError.unexpectedStatus(status)
        }
    }

    func load() throws -> SavedCredentials? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess else {
            throw KeychainCredentialError.unexpectedStatus(status)
        }
        guard let data = item as? Data else {
            throw KeychainCredentialError.decodeFailed
        }
        return try decode(data)
    }

    func clear() throws {
        let status = SecItemDelete(baseQuery() as CFDictionary)
        if status == errSecItemNotFound {
            return
        }
        guard status == errSecSuccess else {
            throw KeychainCredentialError.unexpectedStatus(status)
        }
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func encode(_ credentials: SavedCredentials) throws -> Data {
        let payload = [
            "username": credentials.username,
            "password": credentials.password,
        ]
        guard JSONSerialization.isValidJSONObject(payload) else {
            throw KeychainCredentialError.encodeFailed
        }
        return try JSONSerialization.data(withJSONObject: payload)
    }

    private func decode(_ data: Data) throws -> SavedCredentials {
        guard
            let payload = try JSONSerialization.jsonObject(with: data) as? [String: String],
            let username = payload["username"],
            let password = payload["password"]
        else {
            throw KeychainCredentialError.decodeFailed
        }
        return SavedCredentials(username: username, password: password)
    }
}
