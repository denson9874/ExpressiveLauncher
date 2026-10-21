import SwiftUI
import CryptoKit

public struct KeyInspectorView: View {
    @ObservedObject var keyStore = KeyStore.shared
    @ObservedObject var adb = AdbBridge.shared

    @State private var inputKey: String = ""
    @State private var inspectedDetails: LicenseDetails?
    @State private var inspectError: String?
    @State private var registerStatus: String?
    @State private var isRegistering: Bool = false

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Key Inspector & Verifier")
                        .font(.system(size: 22, weight: .bold))
                    Text("Decode binary payload, check CRC-16 checksum, and cryptographically verify signature.")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }

                // Input Card
                VStack(alignment: .leading, spacing: 14) {
                    Text("ENTER LICENSE KEY")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.secondary)

                    HStack(spacing: 10) {
                        TextField("EXPR-PRO-XXXX-XXXX-XXXX-...", text: $inputKey)
                            .textFieldStyle(.roundedBorder)
                            .font(.system(size: 13, design: .monospaced))

                        Button("Inspect") {
                            runInspection()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(inputKey.trimmingCharacters(in: .whitespaces).isEmpty)

                        Button("Paste") {
                            if let str = NSPasteboard.general.string(forType: .string) {
                                inputKey = str.trimmingCharacters(in: .whitespacesAndNewlines)
                                runInspection()
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                }
                .padding(20)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))

                // Error Banner
                if let err = inspectError {
                    HStack {
                        Image(systemName: "xmark.octagon.fill").foregroundColor(.red)
                        Text(err).font(.system(size: 13, weight: .semibold)).foregroundColor(.red)
                    }
                    .padding(14)
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(8)
                }

                // Details Card
                if let info = inspectedDetails {
                    VStack(alignment: .leading, spacing: 16) {
                        HStack {
                            Text("Cryptographic Verification")
                                .font(.system(size: 15, weight: .bold))
                            Spacer()

                            if info.isValid {
                                HStack(spacing: 4) {
                                    Image(systemName: "checkmark.seal.fill")
                                    Text("SIGNATURE VALID")
                                }
                                .font(.system(size: 12, weight: .bold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.green.opacity(0.2))
                                .foregroundColor(.green)
                                .cornerRadius(8)
                            } else {
                                HStack(spacing: 4) {
                                    Image(systemName: "exclamationmark.shield.fill")
                                    Text("INVALID SIGNATURE")
                                }
                                .font(.system(size: 12, weight: .bold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.red.opacity(0.2))
                                .foregroundColor(.red)
                                .cornerRadius(8)
                            }

                            if info.isExpired {
                                Text("EXPIRED")
                                    .font(.system(size: 12, weight: .bold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(Color.orange.opacity(0.2))
                                    .foregroundColor(.orange)
                                    .cornerRadius(8)
                            }
                        }

                        Divider()

                        // Metadata Grid
                        Grid(alignment: .leading, horizontalSpacing: 24, verticalSpacing: 10) {
                            GridRow {
                                Text("Recipient:").foregroundColor(.secondary)
                                Text(info.recipient).bold().textSelection(.enabled)
                            }
                            if let dev = info.boundDevice {
                                GridRow {
                                    Text("Bound Device ID:").foregroundColor(.secondary)
                                    Text(dev).font(.system(size: 13, design: .monospaced)).textSelection(.enabled)
                                }
                            }
                            if let acc = info.boundAccount {
                                GridRow {
                                    Text("Bound Account:").foregroundColor(.secondary)
                                    Text(acc).textSelection(.enabled)
                                }
                            }
                            GridRow {
                                Text("License Tier:").foregroundColor(.secondary)
                                Text(info.type.name).bold()
                            }
                            GridRow {
                                Text("Issued At:").foregroundColor(.secondary)
                                Text(info.issuedAt.formatted(date: .abbreviated, time: .standard))
                            }
                            GridRow {
                                Text("Expires:").foregroundColor(.secondary)
                                Text(info.expiresAt != nil ? info.expiresAt!.formatted(date: .abbreviated, time: .standard) : "Lifetime (Never)")
                            }
                            GridRow {
                                Text("Features Bitmask:").foregroundColor(.secondary)
                                Text(String(format: "0x%08X", info.features)).font(.system(size: 12, design: .monospaced))
                            }
                        }
                        .font(.system(size: 13))

                        Divider()

                        // Action Buttons: Push to KV & Push to Phone
                        HStack(spacing: 12) {
                            Button(action: registerKeyToCloudflare) {
                                HStack {
                                    if isRegistering { ProgressView().controlSize(.small) }
                                    Text("Register Key in Cloudflare KV")
                                }
                            }
                            .buttonStyle(.bordered)
                            .disabled(isRegistering || keyStore.adminToken.isEmpty)

                            if let dev = adb.selectedDevice, dev.isAvailable {
                                Button("Push to \(dev.model) via ADB") {
                                    let cleanKey = inputKey.trimmingCharacters(in: .whitespacesAndNewlines)
                                    let dl = "expressive://pro/activate?key=\(cleanKey)"
                                    let (ok, msg) = adb.pushActivationToDevice(serial: dev.id, deepLink: dl)
                                    registerStatus = ok ? "Pushed to \(dev.model) successfully!" : "Push failed: \(msg)"
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }

                        if let st = registerStatus {
                            Text(st).font(.system(size: 12)).foregroundColor(.green)
                        }
                    }
                    .padding(20)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(12)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))
                }
            }
            .padding(24)
        }
    }

    private func runInspection() {
        inspectError = nil
        registerStatus = nil
        inspectedDetails = nil

        let clean = inputKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }

        do {
            let details = try LicenseEncoder.inspectKey(clean, publicKey: keyStore.publicKey)
            self.inspectedDetails = details
        } catch {
            self.inspectError = error.localizedDescription
        }
    }

    private func registerKeyToCloudflare() {
        guard let info = inspectedDetails else { return }
        isRegistering = true
        registerStatus = nil

        Task {
            do {
                var item: [String: Any] = ["key": inputKey.trimmingCharacters(in: .whitespacesAndNewlines)]
                if let dev = info.boundDevice { item["device"] = dev }

                let res = try await CloudflareClient.shared.registerKeys(
                    items: [item],
                    baseUrl: keyStore.workerUrl,
                    adminToken: keyStore.adminToken
                )
                await MainActor.run {
                    self.isRegistering = false
                    self.registerStatus = res.success ? "Registered into Cloudflare KV successfully!" : (res.message ?? "Failed")
                }
            } catch {
                await MainActor.run {
                    self.isRegistering = false
                    self.registerStatus = "Registration failed: \(error.localizedDescription)"
                }
            }
        }
    }
}
