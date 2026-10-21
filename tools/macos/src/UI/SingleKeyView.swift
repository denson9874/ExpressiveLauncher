import SwiftUI
import CryptoKit

public struct SingleKeyView: View {
    @ObservedObject var keyStore = KeyStore.shared
    @ObservedObject var adb = AdbBridge.shared

    @State private var bindingMode: String = "device"
    @State private var targetValue: String = "DEV-B0TY-D235"
    @State private var selectedType: LicenseType = .cakey
    @State private var selectedExpires: String = "never"

    @State private var isGenerating: Bool = false
    @State private var generatedKey: String?
    @State private var generatedDeepLink: String?
    @State private var wasRegistered: Bool = false
    @State private var errorMessage: String?
    @State private var statusMessage: String?
    @State private var adbPushSuccess: Bool?

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Header
                VStack(alignment: .leading, spacing: 4) {
                    Text("Single Key Generator")
                        .font(.system(size: 22, weight: .bold))
                    Text("Generate NIST P-256 digital signatures locally via CryptoKit and register to Cloudflare KV.")
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                }

                // Configuration Card
                VStack(alignment: .leading, spacing: 18) {
                    // Target Binding
                    VStack(alignment: .leading, spacing: 8) {
                        Text("BINDING TARGET")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.secondary)

                        Picker("Mode", selection: $bindingMode) {
                            Text("Device Hardware ID").tag("device")
                            Text("Account Email").tag("account")
                            Text("Universal (Name / Handle)").tag("universal")
                        }
                        .pickerStyle(.segmented)

                        HStack(spacing: 8) {
                            TextField(bindingMode == "device" ? "DEV-XXXX-XXXX" : (bindingMode == "account" ? "user@example.com" : "Beta Tester"), text: $targetValue)
                                .textFieldStyle(.roundedBorder)
                                .font(.system(size: 13, design: .monospaced))

                            if bindingMode == "device" && !adb.connectedDevices.isEmpty {
                                Menu {
                                    ForEach(adb.connectedDevices) { dev in
                                        Button("\(dev.displayName)") {
                                            if let fetched = adb.fetchActiveDeviceLicenseId(serial: dev.id) {
                                                targetValue = fetched
                                                statusMessage = "Found ID from device: \(fetched)"
                                            } else {
                                                statusMessage = "Could not detect active ID; enter manually"
                                            }
                                        }
                                    }
                                } label: {
                                    Label("From Phone", systemImage: "iphone")
                                }
                                .menuStyle(.borderedButton)
                            }
                        }
                    }

                    // Type & Expiry Grid
                    HStack(spacing: 16) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("LICENSE TIER")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.secondary)
                            Picker("", selection: $selectedType) {
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
                            Picker("", selection: $selectedExpires) {
                                Text("Lifetime (Never)").tag("never")
                                Text("7 Days").tag("7d")
                                Text("30 Days").tag("30d")
                                Text("90 Days").tag("90d")
                                Text("1 Year").tag("1y")
                            }
                            .labelsHidden()
                        }
                    }

                    // Action Button
                    Button(action: generateKey) {
                        HStack {
                            if isGenerating {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "signature")
                            }
                            Text("Issue Key via Activation Worker")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(isGenerating || targetValue.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(20)
                .background(Color(NSColor.controlBackgroundColor))
                .cornerRadius(12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))

                // Error or Status Banners
                if let err = errorMessage {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.red)
                        Text(err).font(.system(size: 12)).foregroundColor(.red)
                    }
                    .padding(10)
                    .background(Color.red.opacity(0.1))
                    .cornerRadius(8)
                }

                if let st = statusMessage {
                    HStack {
                        Image(systemName: "info.circle.fill").foregroundColor(.blue)
                        Text(st).font(.system(size: 12)).foregroundColor(.primary)
                    }
                    .padding(10)
                    .background(Color.blue.opacity(0.1))
                    .cornerRadius(8)
                }

                // Generated Result Card
                if let key = generatedKey, let dl = generatedDeepLink {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Text("Generated License Key")
                                .font(.system(size: 14, weight: .bold))
                            Spacer()
                            if wasRegistered {
                                Text("Cloudflare KV Registered")
                                    .font(.system(size: 11, weight: .semibold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color.green.opacity(0.2))
                                    .foregroundColor(.green)
                                    .cornerRadius(6)
                            } else {
                                Text("Offline Signed Only")
                                    .font(.system(size: 11, weight: .semibold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color.orange.opacity(0.2))
                                    .foregroundColor(.orange)
                                    .cornerRadius(6)
                            }
                        }

                        // Key Textbox
                        HStack {
                            Text(key)
                                .font(.system(size: 13, design: .monospaced))
                                .textSelection(.enabled)
                                .foregroundColor(.accentColor)
                            Spacer()
                            Button("Copy Key") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(key, forType: .string)
                                statusMessage = "License key copied to clipboard!"
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(12)
                        .background(Color(NSColor.textBackgroundColor))
                        .cornerRadius(8)

                        // Deep Link & ADB Delivery
                        HStack(spacing: 10) {
                            Button(action: {
                                if let url = URL(string: dl) {
                                    NSWorkspace.shared.open(url)
                                }
                            }) {
                                Label("Open Deep Link", systemImage: "arrow.up.right.square")
                            }
                            .buttonStyle(.bordered)

                            Button("Copy Link") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(dl, forType: .string)
                                statusMessage = "Deep link copied to clipboard!"
                            }
                            .buttonStyle(.bordered)

                            Spacer()

                            // ADB 1-Click Phone Push
                            if let dev = adb.selectedDevice, dev.isAvailable {
                                Button(action: {
                                    let (ok, msg) = adb.pushActivationToDevice(serial: dev.id, deepLink: dl)
                                    adbPushSuccess = ok
                                    statusMessage = ok ? "Pushed to \(dev.model) successfully!" : "Push failed: \(msg)"
                                }) {
                                    Label("Push to \(dev.model)", systemImage: "bolt.fill")
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                    }
                    .padding(20)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(12)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.accentColor.opacity(0.3), lineWidth: 1))
                }
            }
            .padding(24)
        }
    }

    private func generateKey() {
        errorMessage = nil
        statusMessage = nil
        isGenerating = true

        let target = targetValue.trimmingCharacters(in: .whitespacesAndNewlines)
        var recipient = target
        var boundDevice: String? = nil

        if bindingMode == "device" {
            recipient = "device:\(target.uppercased())"
            boundDevice = target.uppercased()
        } else if bindingMode == "account" {
            recipient = "account:\(target.lowercased())"
        }

        // Calculate expiration seconds
        var expiresSec: UInt32 = 0
        let now = UInt32(Date().timeIntervalSince1970)
        switch selectedExpires {
        case "7d": expiresSec = now + 7 * 86400
        case "30d": expiresSec = now + 30 * 86400
        case "90d": expiresSec = now + 90 * 86400
        case "1y": expiresSec = now + 365 * 86400
        default: expiresSec = 0
        }

        // The worker signs with the Pro master key and records the key (and its device, if bound).
        guard !keyStore.adminToken.isEmpty else {
            errorMessage = "Add the worker's ADMIN_TOKEN in Settings first."
            isGenerating = false
            return
        }
        _ = (recipient, boundDevice, expiresSec)

        Task {
            do {
                let res = try await CloudflareClient.shared.issueKeyRemote(
                    type: selectedType.name,
                    recipient: bindingMode == "universal" ? target : nil,
                    device: bindingMode == "device" ? target : nil,
                    account: bindingMode == "account" ? target : nil,
                    expires: selectedExpires,
                    baseUrl: keyStore.workerUrl,
                    adminToken: keyStore.adminToken
                )
                await MainActor.run {
                    if let issued = res.keys?.first {
                        self.generatedKey = issued.key
                        self.generatedDeepLink = issued.deepLink ?? "expressive://pro/activate?key=\(issued.key)"
                        self.wasRegistered = true
                        self.statusMessage = "Issued by the activation worker."
                    } else {
                        self.errorMessage = res.message ?? res.error ?? "No key returned"
                    }
                    self.isGenerating = false
                }
            } catch {
                await MainActor.run {
                    self.errorMessage = "Could not reach the worker: \(error.localizedDescription)"
                    self.isGenerating = false
                }
            }
        }
    }
}
