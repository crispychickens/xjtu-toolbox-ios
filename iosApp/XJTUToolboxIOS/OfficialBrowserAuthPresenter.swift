import AuthenticationServices
import Foundation
import UIKit

enum OfficialBrowserAuthError: Error {
    case invalidLoginURL
    case failedToStart
    case missingCallbackURL
}

@MainActor
final class OfficialBrowserAuthPresenter: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authenticate(request: SharedBrowserAuthRequest) async throws -> URL {
        guard let loginURL = URL(string: request.loginURL) else {
            throw OfficialBrowserAuthError.invalidLoginURL
        }

        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: loginURL,
                callbackURLScheme: request.callbackScheme
            ) { callbackURL, error in
                self.session = nil
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                guard let callbackURL else {
                    continuation.resume(throwing: OfficialBrowserAuthError.missingCallbackURL)
                    return
                }
                continuation.resume(returning: callbackURL)
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session

            if !session.start() {
                self.session = nil
                continuation.resume(throwing: OfficialBrowserAuthError.failedToStart)
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}
