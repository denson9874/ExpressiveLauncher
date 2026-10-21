import Foundation

public struct WorkerHealth: Codable {
    public let status: String
    public let service: String?
    public let version: String?
    public let timestamp: String?
}

public struct LicenseLookupResponse: Codable {
    public let success: Bool
    public let key: String?
    public let recipient: String?
    public let createdAt: String?
    public let message: String?
}

public struct AdminRegisterItem: Codable {
    public let key: String
    public let recipient: String?
    public let device: String?
    public let type: String?
    public let expiresAt: Int?
    public let deepLink: String?
}

public struct AdminRegisterResponse: Codable {
    public let success: Bool
    public let registered: Int?
    public let keys: [AdminRegisterItem]?
    public let error: String?
    public let message: String?
}

public struct AdminIssueResponse: Codable {
    public let success: Bool
    public let type: String?
    public let expiresAt: Int?
    public let keys: [AdminRegisterItem]?
    public let error: String?
    public let message: String?
}

public struct IssuedRecord: Codable, Identifiable {
    public let id: String
    public let type: String?
    public let expiresAt: Int?
    public let createdAt: String?
    public let keys: [AdminRegisterItem]
}

public struct IssuedListResponse: Codable {
    public let success: Bool
    public let records: [IssuedRecord]?
    public let cursor: String?
    public let error: String?
    public let message: String?
}

public final class CloudflareClient {
    public static let shared = CloudflareClient()

    private let session: URLSession

    private init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = 30
        self.session = URLSession(configuration: config)
    }

    private func makeUrl(_ path: String, baseUrl: String) -> URL? {
        let base = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return URL(string: "\(base)\(path)")
    }

    public func checkHealth(baseUrl: String) async throws -> (health: WorkerHealth, latencyMs: Int) {
        guard let url = makeUrl("/health", baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")

        let startTime = CFAbsoluteTimeGetCurrent()
        let (data, response) = try await session.data(for: req)
        let elapsed = Int((CFAbsoluteTimeGetCurrent() - startTime) * 1000)

        guard let httpRes = response as? HTTPURLResponse, (200...299).contains(httpRes.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let health = try JSONDecoder().decode(WorkerHealth.self, from: data)
        return (health, elapsed)
    }

    public func lookupLicense(deviceId: String, baseUrl: String) async throws -> LicenseLookupResponse {
        let cleanDev = deviceId.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard let url = makeUrl("/api/license?device_id=\(cleanDev)", baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")

        let (data, _) = try await session.data(for: req)
        return try JSONDecoder().decode(LicenseLookupResponse.self, from: data)
    }

    public func registerKeys(
        items: [[String: Any]],
        baseUrl: String,
        adminToken: String
    ) async throws -> AdminRegisterResponse {
        guard let url = makeUrl("/api/admin/register", baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(adminToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")

        if items.count == 1 {
            req.httpBody = try JSONSerialization.data(withJSONObject: items[0], options: [])
        } else {
            req.httpBody = try JSONSerialization.data(withJSONObject: ["keys": items], options: [])
        }

        let (data, response) = try await session.data(for: req)
        if let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 401 {
            throw NSError(domain: "Cloudflare", code: 401, userInfo: [NSLocalizedDescriptionKey: "Unauthorized: Invalid ADMIN_TOKEN"])
        }
        return try JSONDecoder().decode(AdminRegisterResponse.self, from: data)
    }

    public func issueKeyRemote(
        type: String,
        recipient: String?,
        device: String?,
        account: String?,
        expires: String,
        baseUrl: String,
        adminToken: String
    ) async throws -> AdminIssueResponse {
        guard let url = makeUrl("/api/admin/issue", baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(adminToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")

        var body: [String: Any] = [
            "type": type,
            "expires": expires
        ]
        if let dev = device, !dev.isEmpty { body["device"] = dev.uppercased() }
        else if let acc = account, !acc.isEmpty { body["account"] = acc.lowercased() }
        else if let rec = recipient, !rec.isEmpty { body["recipient"] = rec }

        req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        let (data, response) = try await session.data(for: req)
        if let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 401 {
            throw NSError(domain: "Cloudflare", code: 401, userInfo: [NSLocalizedDescriptionKey: "Unauthorized: Invalid ADMIN_TOKEN"])
        }
        return try JSONDecoder().decode(AdminIssueResponse.self, from: data)
    }

    /// POST /api/admin/issue with count/prefix: the worker signs every key in the batch.
    public func issueBatchRemote(
        type: String,
        count: Int,
        prefix: String,
        expires: String,
        baseUrl: String,
        adminToken: String
    ) async throws -> AdminIssueResponse {
        guard let url = makeUrl("/api/admin/issue", baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("Bearer \(adminToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")
        let body: [String: Any] = ["type": type, "count": count, "prefix": prefix, "expires": expires]
        req.httpBody = try JSONSerialization.data(withJSONObject: body, options: [])
        let (data, response) = try await session.data(for: req)
        if let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 401 {
            throw NSError(domain: "Cloudflare", code: 401, userInfo: [NSLocalizedDescriptionKey: "Unauthorized: Invalid ADMIN_TOKEN"])
        }
        return try JSONDecoder().decode(AdminIssueResponse.self, from: data)
    }

    /// GET /api/admin/issued: every issue request the worker recorded, newest first.
    public func listIssued(baseUrl: String, adminToken: String, cursor: String? = nil) async throws -> IssuedListResponse {
        var path = "/api/admin/issued"
        if let c = cursor, let enc = c.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) { path += "?cursor=\(enc)" }
        guard let url = makeUrl(path, baseUrl: baseUrl) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(adminToken)", forHTTPHeaderField: "Authorization")
        req.setValue("ExpressiveKeyStudio-Native/1", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await session.data(for: req)
        if let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 401 {
            throw NSError(domain: "Cloudflare", code: 401, userInfo: [NSLocalizedDescriptionKey: "Unauthorized: Invalid ADMIN_TOKEN"])
        }
        return try JSONDecoder().decode(IssuedListResponse.self, from: data)
    }
}
