import SwiftUI
import CryptoKit
import AppKit

public struct SettingsView: View {
    @ObservedObject var keyStore = KeyStore.shared
    @ObservedObject var adb = AdbBridge.shared

    @State private var workerUrlInput: String = ""
    @State private var adminTokenInput: String = ""
    @State private var isTokenVisible: Bool = false
    @State private var statusFeedback: String?
    @State private var isTestingWorker: Bool = false
    @State private var testResult: String?

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Settings & Keys")
                        .font(.system(size: 22, weight: .bold))
                    Text("Configure the activation worker connection and ADB toolchain.")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }

                if let fb = statusFeedback {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                        Text(fb)
                            .font(.system(size: 12, weight: .medium))
                    }
                    .padding(10)
                    .background(Color.green.opacity(0.12))
                    .cornerRadius(8)
                }

                // Section 1: Signing
                VStack(alignment: .leading, spacing: 14) {
                    Label("Signing", systemImage: "key.fill")
                        .font(.system(size: 15, weight: .semibold))

                    VStack(alignment: .leading, spacing: 10) {
                        Text("Keys are signed by the activation worker, which holds the only copy of the Pro master key. This app never loads or creates a private key: a key signed with any other key is rejected by every shipped build.")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)

                        if let pubKey = keyStore.publicKey {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Public key the launcher verifies against (ProLicenseVerifier.kt):")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(.secondary)
                                Text(pubKey.derRepresentation.base64EncodedString())
                                    .font(.system(size: 11, design: .monospaced))
                                    .padding(8)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(Color.primary.opacity(0.04))
                                    .cornerRadius(6)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    .padding(14)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(10)
                }

                // Section 2: Cloudflare Worker Credentials
                VStack(alignment: .leading, spacing: 14) {
                    Label("Cloudflare Worker & KV Sync", systemImage: "cloud.fill")
                        .font(.system(size: 15, weight: .semibold))

                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text("Worker URL:")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 100, alignment: .leading)
                            TextField("https://...", text: $workerUrlInput)
                                .textFieldStyle(.roundedBorder)
                                .font(.system(size: 12))
                        }

                        HStack {
                            Text("Admin Token:")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 100, alignment: .leading)

                            if isTokenVisible {
                                TextField("ADMIN_TOKEN", text: $adminTokenInput)
                                    .textFieldStyle(.roundedBorder)
                                    .font(.system(size: 12, design: .monospaced))
                            } else {
                                SecureField("ADMIN_TOKEN", text: $adminTokenInput)
                                    .textFieldStyle(.roundedBorder)
                                    .font(.system(size: 12, design: .monospaced))
                            }

                            Button(action: { isTokenVisible.toggle() }) {
                                Image(systemName: isTokenVisible ? "eye.slash" : "eye")
                            }
                        }

                        if let test = testResult {
                            HStack {
                                Image(systemName: test.contains("OK") ? "checkmark.circle.fill" : "xmark.circle.fill")
                                    .foregroundColor(test.contains("OK") ? .green : .red)
                                Text(test)
                                    .font(.system(size: 12))
                            }
                            .padding(8)
                            .background(Color.primary.opacity(0.04))
                            .cornerRadius(6)
                        }

                        HStack(spacing: 12) {
                            Button(action: saveWorkerConfig) {
                                Label("Save Configuration", systemImage: "square.and.arrow.down")
                            }
                            .buttonStyle(.borderedProminent)

                            Button(action: testWorkerConnection) {
                                if isTestingWorker {
                                    ProgressView().scaleEffect(0.7)
                                } else {
                                    Label("Test Endpoint", systemImage: "network")
                                }
                            }
                            .disabled(isTestingWorker)
                        }
                        .padding(.top, 4)
                    }
                    .padding(14)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(10)
                }

                // Section 3: Android Debug Bridge (ADB)
                VStack(alignment: .leading, spacing: 14) {
                    Label("Android Debug Bridge (ADB)", systemImage: "cable.connector")
                        .font(.system(size: 15, weight: .semibold))

                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            Text("ADB Path:")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 100, alignment: .leading)
                            Text(adb.adbPath ?? "Not Detected (Install via brew install android-platform-tools)")
                                .font(.system(size: 12, design: .monospaced))
                                .foregroundColor(adb.adbPath != nil ? .primary : .red)
                            Spacer()
                        }

                        HStack {
                            Text("Connected Devices:")
                                .font(.system(size: 12, weight: .semibold))
                                .frame(width: 100, alignment: .leading)
                            Text("\(adb.connectedDevices.count) device(s) detected (\(adb.connectedDevices.filter({ $0.isAvailable }).count) authorized)")
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                            Spacer()
                            Button("Rescan ADB") {
                                adb.refreshDevices()
                                setStatus("Scanned for ADB devices.")
                            }
                        }
                    }
                    .padding(14)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(10)
                }

                // Section 4: Engine Architecture & Environment
                VStack(alignment: .leading, spacing: 10) {
                    Label("Architecture & Diagnostics", systemImage: "cpu")
                        .font(.system(size: 15, weight: .semibold))

                    VStack(alignment: .leading, spacing: 6) {
                        diagRow(label: "Crypto Engine", value: "Apple CryptoKit (NIST P-256 ECDSA / SHA-256)")
                        diagRow(label: "Encoding Scheme", value: "Crockford Base32 + CRC-16-CCITT Checksum")
                        diagRow(label: "macOS Target", value: "Universal Native Binary (Apple Silicon / Intel)")
                        diagRow(label: "Signature Standard", value: "ANSI X9.62 ASN.1 DER (ECDSA-Sig-Value)")
                        diagRow(label: "Target Launcher App", value: "app.lawnchair.LawnchairLauncher")
                    }
                    .padding(14)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(10)
                }
            }
            .padding(24)
        }
        .onAppear {
            workerUrlInput = keyStore.workerUrl
            adminTokenInput = keyStore.adminToken
        }
    }

    private func diagRow(label: String, value: String) -> some View {
        HStack {
            Text(label + ":")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(.secondary)
                .frame(width: 140, alignment: .leading)
            Text(value)
                .font(.system(size: 12, design: .monospaced))
            Spacer()
        }
    }

    private func saveWorkerConfig() {
        keyStore.saveWorkerUrl(workerUrlInput)
        do {
            try keyStore.saveAdminToken(adminTokenInput)
            setStatus("Saved Worker URL and Admin Token to ~/.config/expressive/admin_token")
        } catch {
            setStatus("Failed to save admin token: \(error)")
        }
    }

    private func testWorkerConnection() {
        isTestingWorker = true
        testResult = nil
        Task {
            do {
                let (health, latency) = try await CloudflareClient.shared.checkHealth(baseUrl: workerUrlInput)
                await MainActor.run {
                    self.testResult = "Worker OK (\(health.status.uppercased())) — Latency: \(latency)ms"
                    self.isTestingWorker = false
                }
            } catch {
                await MainActor.run {
                    self.testResult = "Connection Failed: \(error.localizedDescription)"
                    self.isTestingWorker = false
                }
            }
        }
    }

    private func setStatus(_ msg: String) {
        statusFeedback = msg
        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
            if self.statusFeedback == msg {
                self.statusFeedback = nil
            }
        }
    }
}
