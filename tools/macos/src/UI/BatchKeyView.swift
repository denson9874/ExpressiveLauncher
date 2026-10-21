import SwiftUI
import CryptoKit

struct BatchItem: Identifiable {
    let id = UUID()
    let recipient: String
    let key: String
    let deepLink: String
}

public struct BatchKeyView: View {
    @ObservedObject var keyStore = KeyStore.shared

    @State private var batchCount: Int = 10
    @State private var batchPrefix: String = "xda-promo-"
    @State private var batchType: LicenseType = .giveaway
    @State private var batchExpires: String = "never"

    @State private var isProcessing: Bool = false
    @State private var generatedItems: [BatchItem] = []
    @State private var statusMessage: String?
    @State private var errorMessage: String?

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Batch License Studio")
                        .font(.system(size: 22, weight: .bold))
                    Text("Generate promotional, tester, or giveaway keys in bulk with automatic Cloudflare KV upload.")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }

                // Configuration Form
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("COUNT (1–100)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            Stepper("\(batchCount) keys", value: $batchCount, in: 1...100)
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("RECIPIENT PREFIX")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            TextField("Prefix", text: $batchPrefix)
                                .textFieldStyle(.roundedBorder)
                        }
                    }

                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("LICENSE TIER")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            Picker("", selection: $batchType) {
                                ForEach(LicenseType.issuable) { type in
                                    Text("\(type.name)").tag(type)
                                }
                            }
                            .labelsHidden()
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("EXPIRATION")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            Picker("", selection: $batchExpires) {
                                Text("Lifetime (Never)").tag("never")
                                Text("30 Days").tag("30d")
                                Text("90 Days").tag("90d")
                                Text("1 Year").tag("1y")
                            }
                            .labelsHidden()
                        }
                    }

                    Text("Keys are signed by the activation worker with the Pro master key and recorded there, so they appear under Issued Keys.")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)

                    Button(action: generateBatch) {
                        HStack {
                            if isProcessing {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "square.stack.3d.up.fill")
                            }
                            Text("Generate \(batchCount) Keys")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(isProcessing)
                }
                .padding(20)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))

                // Messages
                if let err = errorMessage {
                    Text(err).font(.system(size: 12)).foregroundColor(.red)
                }
                if let st = statusMessage {
                    Text(st).font(.system(size: 12)).foregroundColor(.green)
                }

                // Table of Results
                if !generatedItems.isEmpty {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text("Generated Batch (\(generatedItems.count) keys)")
                                .font(.system(size: 14, weight: .bold))
                            Spacer()

                            Button("Export .txt") {
                                exportFile(format: "txt")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)

                            Button("Export .csv") {
                                exportFile(format: "csv")
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)

                            Button("Copy All") {
                                let allText = generatedItems.map { "\($0.recipient) | \($0.key) | \($0.deepLink)" }.joined(separator: "\n")
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(allText, forType: .string)
                                statusMessage = "All keys copied to clipboard!"
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }

                        // Table
                        VStack(spacing: 0) {
                            ForEach(Array(generatedItems.enumerated()), id: \.element.id) { idx, item in
                                HStack {
                                    Text(item.recipient)
                                        .font(.system(size: 12, weight: .semibold))
                                        .frame(width: 160, alignment: .leading)

                                    Text(item.key)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundColor(.accentColor)
                                        .textSelection(.enabled)

                                    Spacer()

                                    Button("Copy") {
                                        NSPasteboard.general.clearContents()
                                        NSPasteboard.general.setString(item.key, forType: .string)
                                    }
                                    .buttonStyle(.borderless)
                                    .font(.system(size: 11))
                                }
                                .padding(.vertical, 8)
                                .padding(.horizontal, 12)
                                .background(idx % 2 == 0 ? Color.secondary.opacity(0.04) : Color.clear)

                                if idx < generatedItems.count - 1 {
                                    Divider()
                                }
                            }
                        }
                        .background(Color(NSColor.textBackgroundColor))
                        .cornerRadius(8)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.15), lineWidth: 1))
                    }
                    .padding(20)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(12)
                }
            }
            .padding(24)
        }
    }

    private func generateBatch() {
        errorMessage = nil
        statusMessage = nil
        guard !keyStore.adminToken.isEmpty else {
            errorMessage = "Add the worker's ADMIN_TOKEN in Settings first."
            return
        }
        isProcessing = true
        Task {
            do {
                let res = try await CloudflareClient.shared.issueBatchRemote(
                    type: batchType.name,
                    count: batchCount,
                    prefix: batchPrefix,
                    expires: batchExpires,
                    baseUrl: keyStore.workerUrl,
                    adminToken: keyStore.adminToken
                )
                await MainActor.run {
                    self.isProcessing = false
                    if res.success, let keys = res.keys {
                        self.generatedItems = keys.map { BatchItem(recipient: $0.recipient ?? "", key: $0.key, deepLink: $0.deepLink ?? "") }
                        self.statusMessage = "The worker issued \(keys.count) \(res.type ?? batchType.name) keys."
                    } else {
                        self.errorMessage = res.message ?? res.error ?? "The worker refused the batch."
                    }
                }
            } catch {
                await MainActor.run {
                    self.isProcessing = false
                    self.errorMessage = "Could not reach the worker: \(error.localizedDescription)"
                }
            }
        }
    }

    private func exportFile(format: String) {
        let savePanel = NSSavePanel()
        savePanel.allowedContentTypes = [format == "csv" ? .commaSeparatedText : .plainText]
        savePanel.nameFieldStringValue = "expressive_pro_keys_\(Int(Date().timeIntervalSince1970)).\(format)"

        if savePanel.runModal() == .OK, let url = savePanel.url {
            var content = ""
            if format == "csv" {
                content = "Recipient,Key,DeepLink\n" + generatedItems.map { "\"\($0.recipient)\",\"\($0.key)\",\"\($0.deepLink)\"" }.joined(separator: "\n")
            } else {
                content = generatedItems.map { "\($0.recipient) | \($0.key) | \($0.deepLink)" }.joined(separator: "\n")
            }
            try? content.write(to: url, atomically: true, encoding: .utf8)
            statusMessage = "Exported to \(url.lastPathComponent)"
        }
    }
}
