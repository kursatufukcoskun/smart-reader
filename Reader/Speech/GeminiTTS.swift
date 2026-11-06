import Foundation
import AVFoundation
import CryptoKit

/// Gemini ses üretimi (TTS). Üretilen sesler diske AAC olarak kaydedilir; aynı paragraf ikinci kez ücretlendirilmez.
enum GeminiTTS {
    static let sampleRate = 24_000.0

    static var cacheDir: URL {
        let u = Paths.base.appendingPathComponent("tts", isDirectory: true)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static func cacheURL(text: String, voice: String, model: String) -> URL {
        let key = ResponseCache.key("gemini-tts", model, voice, text)
        return cacheDir.appendingPathComponent("\(key).m4a")
    }

    /// Metni seslendirir ve önbellek dosyasının adresini döndürür.
    static func audioFile(text: String, voice: String, model: String) async throws -> URL {
        let url = cacheURL(text: text, voice: voice, model: model)
        if FileManager.default.fileExists(atPath: url.path) { return url }
        let pcm = try await synthesize(text: text, voice: voice, model: model)
        try writeM4A(pcm: pcm, to: url)
        TTSUsage.add(seconds: Double(pcm.count) / 2 / sampleRate, model: model)
        return url
    }

    // MARK: - API

    private static func request(text: String, voice: String, model: String) throws -> URLRequest {
        guard let key = Keychain.get(Keychain.geminiKeyName), !key.isEmpty else { throw ClaudeError.missingKey }
        let name = model.hasPrefix("models/") ? String(model.dropFirst(7)) : model
        let legacy = name.contains("2.5")
        let urlString = legacy
            ? "https://generativelanguage.googleapis.com/v1beta/models/\(name):generateContent"
            : "https://generativelanguage.googleapis.com/v1beta/interactions"
        guard let url = URL(string: urlString) else { throw ClaudeError.badResponse }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 120
        req.setValue(key, forHTTPHeaderField: "x-goog-api-key")
        req.setValue("application/json", forHTTPHeaderField: "content-type")
        let body: [String: Any]
        if legacy {
            body = [
                "contents": [["parts": [["text": text]]]],
                "generationConfig": [
                    "responseModalities": ["AUDIO"],
                    "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": voice]]],
                ],
            ]
        } else {
            body = [
                "model": name,
                "input": [["type": "user_input", "content": [["type": "text", "text": text]]]],
                "response_format": ["type": "audio"],
                "generation_config": ["speech_config": [["voice": voice]]],
            ]
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return req
    }

    /// Yanıt ağacındaki base64 ses verisini bulur.
    private static func findAudio(_ any: Any) -> String? {
        if let d = any as? [String: Any] {
            if let s = d["data"] as? String, s.count > 400 { return s }
            for v in d.values { if let r = findAudio(v) { return r } }
        } else if let a = any as? [Any] {
            for v in a { if let r = findAudio(v) { return r } }
        }
        return nil
    }

    /// 24 kHz, mono, 16 bit PCM döndürür (WAV başlığı varsa atılır).
    static func synthesize(text: String, voice: String, model: String) async throws -> Data {
        let req = try request(text: text, voice: voice, model: model)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw ClaudeError.badResponse }
        guard http.statusCode == 200 else {
            throw ClaudeError.http(http.statusCode, ClaudeClient.apiMessage(String(decoding: data, as: UTF8.self)))
        }
        guard let obj = try? JSONSerialization.jsonObject(with: data),
              let b64 = findAudio(obj), let raw = Data(base64Encoded: b64) else {
            throw ClaudeError.http(0, "Gemini ses verisi döndürmedi.")
        }
        return stripWAVHeader(raw)
    }

    private static func stripWAVHeader(_ d: Data) -> Data {
        guard d.count > 44, d.prefix(4) == Data("RIFF".utf8) else { return d }
        // "data" parçasını ara (başlık 44 bayttan uzun olabilir)
        let tag = Data("data".utf8)
        var i = 12
        while i + 8 <= d.count {
            let id = d.subdata(in: i..<i + 4)
            let size = Int(d[i + 4]) | Int(d[i + 5]) << 8 | Int(d[i + 6]) << 16 | Int(d[i + 7]) << 24
            if id == tag { return d.subdata(in: (i + 8)..<d.count) }
            i += 8 + size + (size & 1)
        }
        return d.subdata(in: 44..<d.count)
    }

    // MARK: - Dosya

    static func writeM4A(pcm: Data, to url: URL) throws {
        let frames = pcm.count / 2
        guard frames > 0,
              let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(frames)),
              let out = buf.floatChannelData?[0] else { throw ClaudeError.badResponse }
        buf.frameLength = AVAudioFrameCount(frames)
        pcm.withUnsafeBytes { raw in
            let s = raw.bindMemory(to: Int16.self)
            for i in 0..<frames { out[i] = Float(Int16(littleEndian: s[i])) / 32768 }
        }
        let tmp = url.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: tmp)
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 48_000,
        ]
        do {
            let file = try AVAudioFile(forWriting: tmp, settings: settings)
            try file.write(from: buf)
        }
        try? FileManager.default.removeItem(at: url)
        try FileManager.default.moveItem(at: tmp, to: url)
    }

    static func cacheSize() -> Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    static func clearCache() {
        try? FileManager.default.removeItem(at: cacheDir)
    }
}

/// Aylık üretilen Gemini sesi ve tahmini maliyet.
enum TTSUsage {
    private static var monthKey: String {
        let c = Calendar.current.dateComponents([.year, .month], from: Date())
        return String(format: "ttsSeconds-%04d-%02d", c.year ?? 0, c.month ?? 0)
    }

    static var secondsThisMonth: Double { UserDefaults.standard.double(forKey: monthKey) }

    static func add(seconds: Double, model: String) {
        UserDefaults.standard.set(secondsThisMonth + seconds, forKey: monthKey)
    }

    /// Ses çıktısı 25 token/sn; milyon token başına USD (resmi fiyat sayfasındaki değerler).
    static func dollarsPerHour(model: String) -> Double {
        let after2027 = Date() >= (Calendar.current.date(from: DateComponents(year: 2027, month: 1, day: 1)) ?? .distantFuture)
        let perMillion: Double
        if model.contains("flash-lite-tts") { perMillion = after2027 ? 12 : 6 }
        else if model.contains("3.8-flash-tts") { perMillion = after2027 ? 18 : 9 }
        else if model.contains("pro") || model.contains("3.1") { perMillion = 20 }
        else { perMillion = 10 }
        return 3600 * 25 / 1_000_000 * perMillion
    }

    static func dollarsThisMonth(model: String) -> Double {
        secondsThisMonth / 3600 * dollarsPerHour(model: model)
    }
}
