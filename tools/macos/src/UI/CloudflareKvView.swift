import SwiftUI

public struct CloudflareKvView: View {
    @ObservedObject var keyStore = KeyStore.shared

    @State private var workerHealth: WorkerHealth?
    @State private var pingLatency: Int?
    @State private var isPinging: Bool = false
    @State private var pingError: String?

    @State private var queryDeviceId: String = ""
    @State private var lookupResponse: LicenseLookupResponse?
    @State private var isSearching: Bool = false
    @State private var lookupError: String?

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Cloudflare Worker & KV Explorer")
                        .font(.system(size: 22, weight: .bold))
                    Text("Real-time telemetry and database inspection for Expressive Launcher activation service.")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }

                // Health & Latency Card
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Service Endpoint")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            Text(keyStore.workerUrl)
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(.primary)
                        }
                        Spacer()

                        Button(action: pingWorker) {
                            HStack(spacing: 6) {
                                if isPinging { ProgressView().controlSize(.small) }
                                else { Image(systemName: "bolt.horizontal.fill") }
                                Text("Ping Worker")
                            }
                        }
                        .buttonStyle(.bordered)
                    }

                    Divider()

                    HStack(spacing: 30) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("STATUS").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                            if let h = workerHealth {
                                HStack(spacing: 6) {
                                    Circle().fill(Color.green).frame(width: 8, height: 8)
                                    Text(h.status.uppercased()).font(.system(size: 13, weight: .bold)).foregroundColor(.green)
                                }
                            } else {
                                Text("Not checked").font(.system(size: 13)).foregroundColor(.secondary)
                            }
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("VERSION").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                            Text(workerHealth?.version ?? "—").font(.system(size: 13, weight: .semibold))
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text("LATENCY").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                            if let lat = pingLatency {
                                Text("\(lat) ms").font(.system(size: 13, weight: .semibold))
                                    .foregroundColor(lat < 300 ? .green : .orange)
                            } else {
                                Text("—").font(.system(size: 13)).foregroundColor(.secondary)
                            }
                        }
                    }

                    if let err = pingError {
                        Text("Connection error: \(err)").font(.system(size: 12)).foregroundColor(.red)
                    }
                }
                .padding(20)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))

                // Device KV Lookup Card
                VStack(alignment: .leading, spacing: 16) {
                    Text("Device License Lookup (KV)")
                        .font(.system(size: 15, weight: .bold))

                    HStack(spacing: 10) {
                        TextField("DEV-XXXX-XXXX", text: $queryDeviceId)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 13, design: .monospaced))

                        Button(action: queryDeviceInKv) {
                            HStack(spacing: 6) {
                                if isSearching { ProgressView().controlSize(.small) }
                                Text("Query KV")
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isSearching || queryDeviceId.trimmingCharacters(in: .whitespaces).isEmpty)
                    }

                    if let res = lookupResponse {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                if res.success {
                                    Label("Active License Found in KV", systemImage: "checkmark.circle.fill")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.green)
                                } else {
                                    Label(res.message ?? "No active license in KV", systemImage: "xmark.circle.fill")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.orange)
                                }
                                Spacer()
                            }

                            if res.success, let key = res.key {
                                Divider()
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("LICENSE KEY")
                                        .font(.system(size: 10, weight: .bold))
                                        .foregroundColor(.secondary)
                                    Text(key)
                                        .font(.system(size: 12, design: .monospaced))
                                        .foregroundColor(.accentColor)
                                        .textSelection(.enabled)

                                    if let recip = res.recipient {
                                        Text("RECIPIENT: \(recip)").font(.system(size: 12))
                                    }
                                    if let created = res.createdAt {
                                        Text("CREATED: \(created)").font(.system(size: 12)).foregroundColor(.secondary)
                                    }
                                }
                            }
                        }
                        .padding(14)
                        .background(Color(NSColor.textBackgroundColor))
                        .cornerRadius(8)
                    }

                    if let err = lookupError {
                        Text("Lookup error: \(err)").font(.system(size: 12)).foregroundColor(.red)
                    }
                }
                .padding(20)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))
            }
            .padding(24)
            .onAppear {
                pingWorker()
            }
        }
    }

    private func pingWorker() {
        isPinging = true
        pingError = nil
        Task {
            do {
                let (h, lat) = try await CloudflareClient.shared.checkHealth(baseUrl: keyStore.workerUrl)
                await MainActor.run {
                    self.workerHealth = h
                    self.pingLatency = lat
                    self.isPinging = false
                }
            } catch {
                await MainActor.run {
                    self.pingError = error.localizedDescription
                    self.isPinging = false
                }
            }
        }
    }

    private func queryDeviceInKv() {
        isSearching = true
        lookupError = nil
        lookupResponse = nil

        let clean = queryDeviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }

        Task {
            do {
                let res = try await CloudflareClient.shared.lookupLicense(deviceId: clean, baseUrl: keyStore.workerUrl)
                await MainActor.run {
                    self.lookupResponse = res
                    self.isSearching = false
                }
            } catch {
                await MainActor.run {
                    self.lookupError = error.localizedDescription
                    self.isSearching = false
                }
            }
        }
    }
}
