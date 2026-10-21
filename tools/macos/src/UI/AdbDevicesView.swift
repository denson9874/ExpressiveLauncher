import SwiftUI

public struct AdbDevicesView: View {
    @ObservedObject var adb = AdbBridge.shared
    @State private var deviceLicenseInfo: [String: String] = [:]
    @State private var statusLog: String = ""

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                // Header
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Connected Android Devices (ADB)")
                            .font(.system(size: 22, weight: .bold))
                        Text("Auto-detect USB/Wi-Fi devices, inspect launcher activation state, and deliver 1-click activations.")
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button(action: {
                        adb.refreshDevices()
                    }) {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(.bordered)
                }

                if let adbPath = adb.adbPath {
                    HStack(spacing: 6) {
                        Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                        Text("ADB Bridge:").foregroundColor(.secondary)
                        Text(adbPath).font(.system(size: 12, design: .monospaced))
                    }
                    .font(.system(size: 12))
                } else {
                    HStack(spacing: 6) {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundColor(.orange)
                        Text("ADB binary not found in standard paths. Ensure Android platform-tools are installed.")
                    }
                    .font(.system(size: 12))
                    .foregroundColor(.orange)
                }

                // Device Cards
                if adb.connectedDevices.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "iphone.slash")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                        Text("No Android devices detected via ADB")
                            .font(.system(size: 15, weight: .semibold))
                        Text("Connect your phone via USB with USB Debugging enabled, then tap Refresh.")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(40)
                    .background(Color(NSColor.controlBackgroundColor))
                    .cornerRadius(12)
                } else {
                    ForEach(adb.connectedDevices) { dev in
                        VStack(alignment: .leading, spacing: 14) {
                            HStack {
                                Image(systemName: "iphone")
                                    .font(.system(size: 20))
                                    .foregroundColor(.accentColor)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dev.displayName)
                                        .font(.system(size: 15, weight: .bold))
                                    Text("Serial: \(dev.id)")
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundColor(.secondary)
                                }
                                Spacer()

                                if dev.isAvailable {
                                    Text("Connected")
                                        .font(.system(size: 11, weight: .bold))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Color.green.opacity(0.2))
                                        .foregroundColor(.green)
                                        .cornerRadius(6)
                                } else {
                                    Text(dev.state.uppercased())
                                        .font(.system(size: 11, weight: .bold))
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .background(Color.orange.opacity(0.2))
                                        .foregroundColor(.orange)
                                        .cornerRadius(6)
                                }
                            }

                            Divider()

                            HStack(spacing: 24) {
                                if let rel = dev.androidVersion {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("ANDROID OS").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                                        Text("Android \(rel)").font(.system(size: 13, weight: .semibold))
                                    }
                                }
                                if let sdk = dev.sdkLevel {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("API LEVEL").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                                        Text("SDK \(sdk)").font(.system(size: 13, weight: .semibold))
                                    }
                                }
                                if let lic = deviceLicenseInfo[dev.id] {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("DEVICE ID").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                                        Text(lic).font(.system(size: 12, design: .monospaced)).foregroundColor(.accentColor)
                                    }
                                }
                            }

                            // Device Action Buttons
                            HStack(spacing: 10) {
                                Button("Read Launcher State") {
                                    if let id = adb.fetchActiveDeviceLicenseId(serial: dev.id) {
                                        deviceLicenseInfo[dev.id] = id
                                        statusLog = "Detected Device ID: \(id)"
                                    } else {
                                        statusLog = "Launcher is running without an active device ID or not in foreground."
                                    }
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)

                                Button("Select as Target Device") {
                                    adb.selectedDevice = dev
                                    statusLog = "Selected \(dev.model) as target for 1-click push."
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                            }
                        }
                        .padding(18)
                        .background(Color(NSColor.controlBackgroundColor))
                        .cornerRadius(12)
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.secondary.opacity(0.15), lineWidth: 1))
                    }
                }

                if !statusLog.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("ADB LOG").font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
                        Text(statusLog)
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundColor(.secondary)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color(NSColor.textBackgroundColor))
                    .cornerRadius(8)
                }
            }
            .padding(24)
        }
    }
}
