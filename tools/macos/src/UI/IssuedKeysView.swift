import SwiftUI
import AppKit

/// Every key the activation worker issued through /api/admin/issue (single, batch and menu bar),
/// read back from the worker's own records so the list is complete whichever tool issued them.
public struct IssuedKeysView: View {
    @ObservedObject var keyStore = KeyStore.shared

    @State private var records: [IssuedRecord] = []
    @State private var cursor: String?
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var filter = ""

    public init() {}

    private struct Row: Identifiable {
        let id: String
        let createdAt: String
        let type: String
        let expires: String
        let recipient: String
        let key: String
    }

    private var rows: [Row] {
        let all = records.flatMap { record in
            record.keys.enumerated().map { index, item in
                Row(
                    id: "\(record.id)#\(index)",
                    createdAt: Self.shortDate(record.createdAt),
                    type: record.type ?? "?",
                    expires: Self.expiry(record.expiresAt),
                    recipient: item.recipient ?? "",
                    key: item.key
                )
            }
        }
        let query = filter.trimmingCharacters(in: .whitespaces).lowercased()
        guard !query.isEmpty else { return all }
        return all.filter { "\($0.recipient) \($0.type) \($0.key)".lowercased().contains(query) }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Issued Keys")
                    .font(.system(size: 22, weight: .bold))
                Text("Every key the activation worker has issued from Key Studio or issue_pro_key.py, newest first.")
                    .font(.system(size: 13))
                    .foregroundColor(.secondary)
            }

            HStack(spacing: 10) {
                TextField("Filter by recipient, device, type or key", text: $filter)
                    .textFieldStyle(.roundedBorder)
                Button(action: { load(reset: true) }) {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .disabled(isLoading)
                Button(action: exportCsv) {
                    Label("Export CSV", systemImage: "square.and.arrow.up")
                }
                .disabled(rows.isEmpty)
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.system(size: 12))
                    .foregroundColor(.red)
            }

            Table(rows) {
                TableColumn("Issued") { Text($0.createdAt).font(.system(size: 11, design: .monospaced)) }
                    .width(min: 120, ideal: 130)
                TableColumn("Type") { Text($0.type).font(.system(size: 11, weight: .semibold)) }
                    .width(min: 60, ideal: 70)
                TableColumn("Expires") { Text($0.expires).font(.system(size: 11)) }
                    .width(min: 70, ideal: 90)
                TableColumn("Recipient") { Text($0.recipient).font(.system(size: 11, design: .monospaced)).textSelection(.enabled) }
                    .width(min: 140, ideal: 200)
                TableColumn("Key") { row in
                    HStack(spacing: 6) {
                        Text(row.key.prefix(24) + "…")
                            .font(.system(size: 11, design: .monospaced))
                        Button(action: { copy(row.key) }) {
                            Image(systemName: "doc.on.doc")
                        }
                        .buttonStyle(.borderless)
                        .help("Copy the full key")
                    }
                }
            }

            HStack {
                Text("\(rows.count) keys shown")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                Spacer()
                if isLoading {
                    ProgressView().controlSize(.small)
                } else if cursor != nil {
                    Button("Load older") { load(reset: false) }
                }
            }
        }
        .padding(24)
        .onAppear { if records.isEmpty { load(reset: true) } }
    }

    private func load(reset: Bool) {
        guard !keyStore.adminToken.isEmpty else {
            errorMessage = "Add the worker's ADMIN_TOKEN in Settings first."
            return
        }
        isLoading = true
        errorMessage = nil
        let from = reset ? nil : cursor
        Task {
            do {
                let res = try await CloudflareClient.shared.listIssued(
                    baseUrl: keyStore.workerUrl, adminToken: keyStore.adminToken, cursor: from
                )
                await MainActor.run {
                    isLoading = false
                    if res.success {
                        records = (reset ? [] : records) + (res.records ?? [])
                        cursor = res.cursor
                    } else {
                        errorMessage = res.message ?? res.error ?? "The worker refused the request."
                    }
                }
            } catch {
                await MainActor.run {
                    isLoading = false
                    errorMessage = "Could not reach the worker: \(error.localizedDescription)"
                }
            }
        }
    }

    private func copy(_ key: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(key, forType: .string)
    }

    private func exportCsv() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.commaSeparatedText]
        panel.nameFieldStringValue = "expressive_issued_keys.csv"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let csv = "Issued,Type,Expires,Recipient,Key\n" + rows.map {
            "\"\($0.createdAt)\",\"\($0.type)\",\"\($0.expires)\",\"\($0.recipient)\",\"\($0.key)\""
        }.joined(separator: "\n")
        try? csv.write(to: url, atomically: true, encoding: .utf8)
    }

    private static func shortDate(_ iso: String?) -> String {
        guard let iso, let date = ISO8601DateFormatter.withFractions.date(from: iso) ?? ISO8601DateFormatter().date(from: iso) else {
            return iso ?? ""
        }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: date)
    }

    private static func expiry(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return "Lifetime" }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(seconds)))
    }
}

private extension ISO8601DateFormatter {
    static let withFractions: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}
