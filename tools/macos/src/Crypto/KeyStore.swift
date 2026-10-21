import Foundation
import CryptoKit

/// Credentials for Key Studio. Keys are signed only by the activation worker, which holds the one
/// master private key (secret PRO_PRIVATE_KEY_PEM); this app never loads or creates a private key,
/// because any other key signs licenses every shipped build rejects.
public final class KeyStore: ObservableObject {
    public static let shared = KeyStore()

    /// The public key the app verifies against (ProLicenseVerifier.PRO_PUBLIC_KEY_BASE64).
    @Published public private(set) var publicKey: P256.Signing.PublicKey?
    @Published public var adminToken: String = ""
    @Published public var workerUrl: String

    public static let defaultKeyDir = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent(".config/expressive", isDirectory: true)
    public static let defaultTokenPath = defaultKeyDir.appendingPathComponent("admin_token").path
    public static let defaultWorkerUrl = "https://expressive-pro-activation.daryldenson-apps.workers.dev"

    // Must match PRO_PUBLIC_KEY_BASE64 in lawnchair/src/app/lawnchair/pro/ProLicenseVerifier.kt.
    public static let embeddedPublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEQ2IKJ1Abq8srwhR39mznSCNHhNbC5RUmM3n51GIrhMZPAOEQwMj8KZ9GVn8S2a9oQ8pJO8nBSfwRkNUbV0PdRA=="

    private init() {
        self.workerUrl = ProcessInfo.processInfo.environment["EXPRESSIVE_PRO_WORKER_URL"] ?? KeyStore.defaultWorkerUrl
        loadCredentials()
    }

    public func loadCredentials() {
        if let envToken = ProcessInfo.processInfo.environment["EXPRESSIVE_PRO_ADMIN_TOKEN"], !envToken.isEmpty {
            self.adminToken = envToken.trimmingCharacters(in: .whitespacesAndNewlines)
        } else if FileManager.default.fileExists(atPath: KeyStore.defaultTokenPath),
                  let fileContent = try? String(contentsOfFile: KeyStore.defaultTokenPath, encoding: .utf8) {
            self.adminToken = fileContent.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        if let derData = Data(base64Encoded: KeyStore.embeddedPublicKeyBase64) {
            self.publicKey = try? P256.Signing.PublicKey(derRepresentation: derData)
        }
    }

    public func saveAdminToken(_ token: String) throws {
        self.adminToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        try FileManager.default.createDirectory(at: KeyStore.defaultKeyDir, withIntermediateDirectories: true)
        try self.adminToken.write(toFile: KeyStore.defaultTokenPath, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: KeyStore.defaultTokenPath)
    }

    public func saveWorkerUrl(_ url: String) {
        self.workerUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
