import Foundation

public struct AdbDevice: Identifiable, Hashable {
    public let id: String // Serial number, e.g. 28231FDH3005GR
    public let model: String // e.g. Pixel_7_Pro
    public let state: String // device, unauthorized, offline
    public let androidVersion: String?
    public let sdkLevel: String?

    public var isAvailable: Bool {
        state == "device"
    }

    public var displayName: String {
        let cleanModel = model.replacingOccurrences(of: "_", with: " ")
        return "\(cleanModel) (\(id))"
    }
}

public final class AdbBridge: ObservableObject {
    public static let shared = AdbBridge()

    @Published public var connectedDevices: [AdbDevice] = []
    @Published public var selectedDevice: AdbDevice?
    @Published public var isPolling: Bool = false
    @Published public var adbPath: String?
    @Published public var lastLog: String = ""

    private var timer: Timer?

    private init() {
        self.adbPath = locateAdb()
        refreshDevices()
    }

    public func locateAdb() -> String? {
        let commonPaths = [
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
            FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Android/sdk/platform-tools/adb").path
        ]
        for path in commonPaths {
            if FileManager.default.isExecutableFile(atPath: path) {
                return path
            }
        }

        // Try `which adb`
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/which")
        process.arguments = ["adb"]
        let pipe = Pipe()
        process.standardOutput = pipe
        if (try? process.run()) != nil {
            process.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            if let output = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !output.isEmpty {
                return output
            }
        }
        return nil
    }

    @discardableResult
    private func runAdbCommand(_ args: [String]) -> (output: String, exitCode: Int32) {
        guard let adb = adbPath else {
            return ("Error: adb not found", -1)
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: adb)
        process.arguments = args
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        do {
            try process.run()
            process.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let str = String(data: data, encoding: .utf8) ?? ""
            return (str, process.terminationStatus)
        } catch {
            return ("Execution failed: \(error)", -1)
        }
    }

    public func refreshDevices() {
        guard adbPath != nil else { return }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let (raw, _) = self.runAdbCommand(["devices", "-l"])
            let lines = raw.components(separatedBy: .newlines)

            var devices: [AdbDevice] = []
            for line in lines {
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty || trimmed.hasPrefix("List of devices") || trimmed.hasPrefix("* daemon") {
                    continue
                }
                let parts = trimmed.split(whereSeparator: { $0.isWhitespace }).map(String.init)
                guard parts.count >= 2 else { continue }
                let serial = parts[0]
                let state = parts[1]

                var model = "Android Device"
                for part in parts {
                    if part.hasPrefix("model:") {
                        model = String(part.dropFirst(6))
                    }
                }

                var release: String? = nil
                var sdk: String? = nil
                if state == "device" {
                    let (rel, _) = self.runAdbCommand(["-s", serial, "shell", "getprop", "ro.build.version.release"])
                    release = rel.trimmingCharacters(in: .whitespacesAndNewlines)
                    let (sdkStr, _) = self.runAdbCommand(["-s", serial, "shell", "getprop", "ro.build.version.sdk"])
                    sdk = sdkStr.trimmingCharacters(in: .whitespacesAndNewlines)
                }

                devices.append(AdbDevice(
                    id: serial,
                    model: model,
                    state: state,
                    androidVersion: release,
                    sdkLevel: sdk
                ))
            }

            DispatchQueue.main.async {
                self.connectedDevices = devices
                if self.selectedDevice == nil || !devices.contains(where: { $0.id == self.selectedDevice?.id }) {
                    self.selectedDevice = devices.first(where: { $0.isAvailable }) ?? devices.first
                }
            }
        }
    }

    public func pushActivationToDevice(serial: String, deepLink: String) -> (success: Bool, message: String) {
        let (output, code) = runAdbCommand([
            "-s", serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", deepLink
        ])
        let ok = code == 0 && !output.lowercased().contains("error")
        return (ok, output.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    public func fetchActiveDeviceLicenseId(serial: String) -> String? {
        let (output, _) = runAdbCommand([
            "-s", serial, "shell", "dumpsys", "activity", "app.lawnchair.LawnchairLauncher"
        ])
        // Match patterns like DEV-XXXX-XXXX
        let pattern = "DEV-[0-9A-Z]{4}-[0-9A-Z]{4}"
        if let regex = try? NSRegularExpression(pattern: pattern),
           let match = regex.firstMatch(in: output, range: NSRange(output.startIndex..., in: output)),
           let range = Range(match.range, in: output) {
            return String(output[range])
        }
        return nil
    }
}
