import Foundation
import CryptoKit

public enum LicenseType: UInt8, CaseIterable, Identifiable {
    case tester = 1
    case giveaway = 2
    case vip = 3
    case dev = 4
    case cakey = 5
    case trial = 6

    public var id: UInt8 { rawValue }

    public var name: String {
        switch self {
        case .tester: return "TESTER"
        case .giveaway: return "GIVEAWAY"
        case .vip: return "VIP"
        case .dev: return "DEV"
        case .cakey: return "CAKEY"
        case .trial: return "TRIAL"
        }
    }

    /// Types the worker issues by hand; trials come only from the app's trial button.
    public static var issuable: [LicenseType] { allCases.filter { $0 != .trial } }

    public static func from(name: String) -> LicenseType? {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return allCases.first { $0.name == clean }
    }
}

public struct LicenseDetails: Identifiable {
    public let id = UUID()
    public let isValid: Bool
    public let type: LicenseType
    public let recipient: String
    public let issuedAt: Date
    public let expiresAt: Date?
    public let features: UInt32
    public let isExpired: Bool
    public let boundDevice: String?
    public let boundAccount: String?
}

public enum LicenseEncoder {
    public static let magic: [UInt8] = [0x45, 0x50] // "EP"
    public static let schemaVersion: UInt8 = 1
    public static let crockfordAlphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    // CRC-16-CCITT (poly 0x1021, init 0xFFFF)
    public static func computeCrc16Ccitt(_ data: Data) -> UInt16 {
        var crc: UInt16 = 0xFFFF
        for byte in data {
            crc ^= UInt16(byte) << 8
            for _ in 0..<8 {
                if (crc & 0x8000) != 0 {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF
                } else {
                    crc = (crc << 1) & 0xFFFF
                }
            }
        }
        return crc
    }

    // Crockford Base32 Encoding
    public static func crockfordEncode(_ data: Data) -> String {
        var bitBuf: UInt64 = 0
        var bitsInBuf: Int = 0
        var chars: [Character] = []

        for byte in data {
            bitBuf = (bitBuf << 8) | UInt64(byte)
            bitsInBuf += 8
            while bitsInBuf >= 5 {
                bitsInBuf -= 5
                let index = Int((bitBuf >> bitsInBuf) & 0x1F)
                chars.append(crockfordAlphabet[index])
            }
        }
        if bitsInBuf > 0 {
            let index = Int((bitBuf << (5 - bitsInBuf)) & 0x1F)
            chars.append(crockfordAlphabet[index])
        }
        return String(chars)
    }

    // Crockford Base32 Decoding with typo tolerance
    public static func crockfordDecode(_ input: String) throws -> Data {
        let decodeMap: [Character: UInt8] = [
            "0": 0, "O": 0, "o": 0,
            "1": 1, "I": 1, "i": 1, "L": 1, "l": 1,
            "2": 2, "3": 3, "4": 4, "5": 5, "6": 6, "7": 7, "8": 8, "9": 9,
            "A": 10, "B": 11, "C": 12, "D": 13, "E": 14, "F": 15, "G": 16, "H": 17,
            "J": 18, "K": 19, "M": 20, "N": 21, "P": 22, "Q": 23, "R": 24, "S": 25,
            "T": 26, "V": 27, "W": 28, "X": 29, "Y": 30, "Z": 31,
            "a": 10, "b": 11, "c": 12, "d": 13, "e": 14, "f": 15, "g": 16, "h": 17,
            "j": 18, "k": 19, "m": 20, "n": 21, "p": 22, "q": 23, "r": 24, "s": 25,
            "t": 26, "v": 27, "w": 28, "x": 29, "y": 30, "z": 31,
        ]

        var bitBuf: UInt64 = 0
        var bitsInBuf: Int = 0
        var out = Data()

        for c in input {
            if let val = decodeMap[c] {
                bitBuf = (bitBuf << 5) | UInt64(val)
                bitsInBuf += 5
                while bitsInBuf >= 8 {
                    bitsInBuf -= 8
                    out.append(UInt8((bitBuf >> bitsInBuf) & 0xFF))
                }
            }
        }
        if bitsInBuf > 0 {
            let paddingMask = (UInt64(1) << bitsInBuf) - 1
            if (bitBuf & paddingMask) != 0 {
                throw LicenseError.invalidPadding
            }
        }
        return out
    }

    // Build binary payload
    public static func buildPayload(
        recipient: String,
        type: LicenseType,
        issuedAt: UInt32,
        expiresAt: UInt32,
        features: UInt32 = 0xFFFFFFFF
    ) throws -> Data {
        guard let recipBytes = recipient.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              recipBytes.count <= 255 else {
            throw LicenseError.recipientTooLong
        }

        var payload = Data()
        payload.append(contentsOf: magic)
        payload.append(schemaVersion)
        payload.append(type.rawValue)

        var issuedBE = issuedAt.bigEndian
        payload.append(Data(bytes: &issuedBE, count: 4))

        var expiresBE = expiresAt.bigEndian
        payload.append(Data(bytes: &expiresBE, count: 4))

        var featuresBE = features.bigEndian
        payload.append(Data(bytes: &featuresBE, count: 4))

        payload.append(UInt8(recipBytes.count))
        payload.append(recipBytes)

        return payload
    }

    // Signing happens only in the activation worker (POST /api/admin/issue); see KeyStore.

    // Verify and inspect key
    public static func inspectKey(
        _ rawKey: String,
        publicKey: P256.Signing.PublicKey? = nil
    ) throws -> LicenseDetails {
        var clean = rawKey.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if clean.hasPrefix("EXPR-PRO-") {
            clean = String(clean.dropFirst(9))
        } else if clean.hasPrefix("EXPR-") {
            clean = String(clean.dropFirst(5))
        }
        clean = clean.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: " ", with: "")

        let raw = try crockfordDecode(clean)
        guard raw.count >= 17 + 1 + 64 + 2 else {
            throw LicenseError.keyTooShort
        }

        let dataWithoutCrc = raw.subdata(in: 0..<(raw.count - 2))
        let expectedCrc = raw.subdata(in: (raw.count - 2)..<raw.count).withUnsafeBytes {
            $0.load(as: UInt16.self).bigEndian
        }
        let actualCrc = computeCrc16Ccitt(dataWithoutCrc)
        guard actualCrc == expectedCrc else {
            throw LicenseError.checksumMismatch
        }

        guard raw[0] == magic[0], raw[1] == magic[1] else {
            throw LicenseError.invalidMagic
        }
        guard raw[2] == schemaVersion else {
            throw LicenseError.unsupportedSchemaVersion(raw[2])
        }

        guard let licenseType = LicenseType(rawValue: raw[3]) else {
            throw LicenseError.unknownType(raw[3])
        }

        let issuedAtSec = raw.subdata(in: 4..<8).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        let expiresAtSec = raw.subdata(in: 8..<12).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        let features = raw.subdata(in: 12..<16).withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
        let recipLen = Int(raw[16])

        let payloadLen = 17 + recipLen
        guard dataWithoutCrc.count >= payloadLen + 1 else {
            throw LicenseError.malformedLength
        }

        let recipData = raw.subdata(in: 17..<(17 + recipLen))
        guard let recipient = String(data: recipData, encoding: .utf8) else {
            throw LicenseError.malformedRecipient
        }

        let payload = raw.subdata(in: 0..<payloadLen)
        let sigLen = Int(raw[payloadLen])
        guard dataWithoutCrc.count >= payloadLen + 1 + sigLen else {
            throw LicenseError.malformedLength
        }

        let sigBytes = raw.subdata(in: (payloadLen + 1)..<(payloadLen + 1 + sigLen))

        var isValidSignature = true
        if let pubKey = publicKey {
            do {
                let ecdsaSig = try P256.Signing.ECDSASignature(derRepresentation: sigBytes)
                isValidSignature = pubKey.isValidSignature(ecdsaSig, for: payload)
            } catch {
                isValidSignature = false
            }
        }

        let issuedDate = Date(timeIntervalSince1970: TimeInterval(issuedAtSec))
        let expiresDate = expiresAtSec > 0 ? Date(timeIntervalSince1970: TimeInterval(expiresAtSec)) : nil
        let isExpired = expiresDate != nil && Date() > expiresDate!

        var boundDevice: String? = nil
        if recipient.lowercased().hasPrefix("device:") {
            boundDevice = String(recipient.dropFirst(7)).trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        }

        var boundAccount: String? = nil
        if recipient.lowercased().hasPrefix("account:") {
            boundAccount = String(recipient.dropFirst(8)).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }

        return LicenseDetails(
            isValid: isValidSignature,
            type: licenseType,
            recipient: recipient,
            issuedAt: issuedDate,
            expiresAt: expiresDate,
            features: features,
            isExpired: isExpired,
            boundDevice: boundDevice,
            boundAccount: boundAccount
        )
    }
}

public enum LicenseError: LocalizedError {
    case invalidPadding
    case recipientTooLong
    case signatureTooLarge
    case keyTooShort
    case checksumMismatch
    case invalidMagic
    case unsupportedSchemaVersion(UInt8)
    case unknownType(UInt8)
    case malformedLength
    case malformedRecipient
    case signatureVerificationFailed

    public var errorDescription: String? {
        switch self {
        case .invalidPadding: return "Invalid padding in Crockford Base32 string"
        case .recipientTooLong: return "Recipient exceeds maximum length of 255 bytes"
        case .signatureTooLarge: return "DER signature exceeds 255 bytes"
        case .keyTooShort: return "Key data is too short to be a valid Expressive Pro key"
        case .checksumMismatch: return "CRC-16 checksum failed. Key contains a typo or corruption"
        case .invalidMagic: return "Invalid magic header bytes (expected 'EP')"
        case .unsupportedSchemaVersion(let v): return "Unsupported license schema version: \(v)"
        case .unknownType(let t): return "Unknown license type: \(t)"
        case .malformedLength: return "Malformed packet lengths"
        case .malformedRecipient: return "Recipient string is not valid UTF-8"
        case .signatureVerificationFailed: return "Cryptographic ECDSA signature verification failed"
        }
    }
}
