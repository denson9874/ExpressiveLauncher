import SwiftUI
import AppKit

public struct MenuBarView: View {
    @ObservedObject var keyStore = KeyStore.shared
    @ObservedObject var adb = AdbBridge.shared

    @State private var statusMessage: String?
    @State private var isProcessing: Bool = false

    public var onOpenMainWindow: (() -> Void)?

    public init(onOpenMainWindow: (() -> Void)? = nil) {
        self.onOpenMainWindow = onOpenMainWindow
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // Header
            HStack(spacing: 8) {
                if let img = NSImage(named: "AppIcon") {
                    Image(nsImage: img)
                        .resizable()
                        .frame(width: 18, height: 18)
                        .cornerRadius(4)
                } else {
                    Image(systemName: "key.horizontal.fill")
                        .foregroundColor(.blue)
                }
                Text("Expressive Key Studio")
                    .font(.system(size: 13, weight: .bold))
                Spacer()
                Button(action: {
                    adb.refreshDevices()
                }) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11))
                }
                .buttonStyle(.plain)
                .help("Rescan ADB Devices")
            }

            Divider()

            // Device Telemetry
            VStack(alignment: .leading, spacing: 4) {
                Text("CONNECTED PHONE")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(.secondary)

                if let dev = adb.selectedDevice {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(dev.isAvailable ? Color.green : Color.orange)
                            .frame(width: 8, height: 8)
                        Text(dev.displayName)
                            .font(.system(size: 12, weight: .semibold))
                            .lineLimit(1)
                    }
                    Text("Android \(dev.androidVersion ?? "17") • \(dev.state)")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                } else {
                    HStack(spacing: 6) {
                        Circle().fill(Color.gray).frame(width: 8, height: 8)
                        Text("No device connected")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }
            }

            Divider()

            // Quick Actions
            VStack(spacing: 8) {
                if let dev = adb.selectedDevice, dev.isAvailable {
                    Button(action: { pushKeyToDevice(dev: dev) }) {
                        HStack {
                            Image(systemName: "paperplane.fill")
                            Text("1-Click Activate Connected Phone")
                            Spacer()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isProcessing)
                }

                Button(action: { generateQuickKeyAndCopy() }) {
                    HStack {
                        Image(systemName: "doc.on.doc.fill")
                        Text("Copy New Universal Key")
                        Spacer()
                    }
                }
                .disabled(isProcessing)
            }

            if let status = statusMessage {
                Text(status)
                    .font(.system(size: 11))
                    .foregroundColor(.blue)
                    .padding(6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.blue.opacity(0.1))
                    .cornerRadius(6)
            }

            Divider()

            // Footer
            HStack {
                Button("Open Studio Window") {
                    onOpenMainWindow?()
                }
                .buttonStyle(.plain)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.accentColor)

                Spacer()

                Button("Quit") {
                    NSApp.terminate(nil)
                }
                .buttonStyle(.plain)
                .font(.system(size: 11))
                .foregroundColor(.secondary)
            }
        }
        .padding(14)
        .frame(width: 280)
    }

    /// Issue a key through the activation worker (it holds the Pro master key).
    private func issueViaWorker(type: String, device: String?, recipient: String?) async throws -> AdminRegisterItem {
        guard !keyStore.adminToken.isEmpty else {
            throw NSError(domain: "KeyStudio", code: 1, userInfo: [NSLocalizedDescriptionKey: "Add the worker's ADMIN_TOKEN in Settings first"])
        }
        let res = try await CloudflareClient.shared.issueKeyRemote(
            type: type, recipient: recipient, device: device, account: nil,
            expires: "never", baseUrl: keyStore.workerUrl, adminToken: keyStore.adminToken
        )
        guard let issued = res.keys?.first else {
            throw NSError(domain: "KeyStudio", code: 2, userInfo: [NSLocalizedDescriptionKey: res.message ?? res.error ?? "No key returned"])
        }
        return issued
    }

    private func pushKeyToDevice(dev: AdbDevice) {
        isProcessing = true
        statusMessage = "Issuing key..."

        let bridge = AdbBridge.shared
        let serial = dev.id
        Task {
            do {
                let targetId = await Task.detached { bridge.fetchActiveDeviceLicenseId(serial: serial) }.value ?? serial
                let isDeviceId = targetId.range(of: "^DEV-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$", options: .regularExpression) != nil
                let issued = try await issueViaWorker(
                    type: "CAKEY",
                    device: isDeviceId ? targetId : nil,
                    recipient: isDeviceId ? nil : targetId
                )
                let deepLink = issued.deepLink ?? "expressive://pro/activate?key=\(issued.key)"
                let result = await Task.detached { bridge.pushActivationToDevice(serial: serial, deepLink: deepLink) }.value
                await MainActor.run {
                    self.isProcessing = false
                    self.statusMessage = result.success ? "Activated on \(dev.model)!" : "Pushed, but am start reported: \(result.message)"
                }
            } catch {
                await MainActor.run {
                    self.isProcessing = false
                    self.statusMessage = "Failed: \(error.localizedDescription)"
                }
            }
        }
    }

    private func generateQuickKeyAndCopy() {
        statusMessage = "Issuing key..."
        Task {
            do {
                let issued = try await issueViaWorker(type: "CAKEY", device: nil, recipient: "UNIVERSAL")
                await MainActor.run {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(issued.key, forType: .string)
                    self.statusMessage = "Copied \(issued.key.prefix(18))... to clipboard"
                }
            } catch {
                await MainActor.run { self.statusMessage = "Issue failed: \(error.localizedDescription)" }
            }
        }
    }
}
