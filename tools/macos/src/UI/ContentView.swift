import SwiftUI

public enum NavigationSection: String, CaseIterable, Identifiable {
    case single = "Single Key"
    case batch = "Batch Generation"
    case issued = "Issued Keys"
    case inspector = "Key Inspector"
    case devices = "ADB Devices"
    case cloudflare = "Cloudflare KV"
    case settings = "Settings"

    public var id: String { rawValue }

    public var systemImage: String {
        switch self {
        case .single: return "key.fill"
        case .batch: return "square.stack.3d.up.fill"
        case .issued: return "list.bullet.rectangle.fill"
        case .inspector: return "magnifyingglass.circle.fill"
        case .devices: return "iphone.and.arrow.forward"
        case .cloudflare: return "cloud.fill"
        case .settings: return "gearshape.fill"
        }
    }
}

public struct ContentView: View {
    @State private var selectedSection: NavigationSection? = .single
    @ObservedObject var keyStore = KeyStore.shared
    @ObservedObject var adb = AdbBridge.shared

    public init() {}

    public var body: some View {
        NavigationSplitView {
            List(selection: $selectedSection) {
                HStack(spacing: 10) {
                    if let img = NSImage(named: "AppIcon") {
                        Image(nsImage: img)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 34, height: 34)
                            .cornerRadius(8)
                    }
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Expressive")
                            .font(.system(size: 14, weight: .bold))
                        Text("License Studio")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.vertical, 4)

                Section("Navigation") {
                    ForEach(NavigationSection.allCases) { section in
                        NavigationLink(value: section) {
                            Label(section.rawValue, systemImage: section.systemImage)
                        }
                    }
                }

                Section("Hardware Telemetry") {
                    if let dev = adb.selectedDevice {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(dev.isAvailable ? Color.green : Color.orange)
                                .frame(width: 8, height: 8)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(dev.displayName)
                                    .font(.system(size: 11, weight: .semibold))
                                    .lineLimit(1)
                                Text("Android \(dev.androidVersion ?? "17") • \(dev.state)")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                    } else {
                        HStack(spacing: 6) {
                            Circle().fill(Color.gray).frame(width: 8, height: 8)
                            Text("No ADB Device")
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .listStyle(.sidebar)
            .frame(minWidth: 200, idealWidth: 220)
            .toolbar {
                ToolbarItem(placement: .automatic) {
                    Button(action: { adb.refreshDevices() }) {
                        Label("Refresh ADB", systemImage: "arrow.clockwise")
                    }
                    .help("Rescan connected USB and Wi-Fi ADB devices")
                }
            }
        } detail: {
            Group {
                switch selectedSection {
                case .single, .none:
                    SingleKeyView()
                case .batch:
                    BatchKeyView()
                case .issued:
                    IssuedKeysView()
                case .inspector:
                    KeyInspectorView()
                case .devices:
                    AdbDevicesView()
                case .cloudflare:
                    CloudflareKvView()
                case .settings:
                    SettingsView()
                }
            }
            .frame(minWidth: 620, idealWidth: 780, minHeight: 520)
        }
    }
}
