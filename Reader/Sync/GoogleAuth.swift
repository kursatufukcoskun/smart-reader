import Foundation
import AuthenticationServices
import CryptoKit
import UIKit

enum SyncError: LocalizedError {
    case cancelled, signedOut, badResponse, http(Int, String), missingBook
    var errorDescription: String? {
        switch self {
        case .cancelled: return "Giriş iptal edildi."
        case .signedOut: return "Google oturumu sona erdi, yeniden bağlanın."
        case .badResponse: return "Google'dan beklenmeyen yanıt geldi."
        case .http(let c, let m): return "Google hatası (\(c)): \(m)"
        case .missingBook: return "Kitap dosyası Drive'da bulunamadı. Diğer cihazın yüklemesini bekleyin."
        }
    }
}

/// Google OAuth 2.0 (PKCE, iOS istemcisi). Yalnızca `drive.appdata` kapsamı.
@MainActor
final class GoogleAuth: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let clientID = "263419260918-img09htao0pqbbt8c1cud1rjo1tn5ffe.apps.googleusercontent.com"
    static let scope = "https://www.googleapis.com/auth/drive.appdata"
    private static let refreshKey = "google_refresh_token"

    static var scheme: String {
        "com.googleusercontent.apps." + clientID.replacingOccurrences(of: ".apps.googleusercontent.com", with: "")
    }
    static var redirectURI: String { scheme + ":/oauth2redirect" }

    private var accessToken: String?
    private var expiry = Date.distantPast
    private var session: ASWebAuthenticationSession?

    var isSignedIn: Bool { Keychain.get(Self.refreshKey) != nil }

    // MARK: Giriş

    func signIn() async throws {
        let verifier = Self.randomString(64)
        let challenge = Self.base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            .init(name: "client_id", value: Self.clientID),
            .init(name: "redirect_uri", value: Self.redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: Self.scope),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "access_type", value: "offline"),
            .init(name: "prompt", value: "consent"),
        ]
        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: c.url!, callbackURLScheme: Self.scheme) { url, err in
                if let url { cont.resume(returning: url) } else { cont.resume(throwing: err ?? SyncError.cancelled) }
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            self.session = s
            if !s.start() { cont.resume(throwing: SyncError.cancelled) }
        }
        guard let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
              let code = items.first(where: { $0.name == "code" })?.value else {
            let err = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "error" })?.value
            throw SyncError.http(0, err ?? "yetki kodu alınamadı")
        }
        try await tokenRequest([
            "client_id": Self.clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": Self.redirectURI,
        ])
    }

    func signOut() {
        Keychain.set("", for: Self.refreshKey)
        accessToken = nil
        expiry = .distantPast
    }

    // MARK: Erişim anahtarı

    func validAccessToken(forceRefresh: Bool = false) async throws -> String {
        if !forceRefresh, let t = accessToken, expiry > Date().addingTimeInterval(60) { return t }
        guard let refresh = Keychain.get(Self.refreshKey) else { throw SyncError.signedOut }
        do {
            try await tokenRequest(["client_id": Self.clientID, "refresh_token": refresh, "grant_type": "refresh_token"])
        } catch SyncError.http(let code, _) where code == 400 || code == 401 {
            signOut()
            throw SyncError.signedOut
        }
        guard let t = accessToken else { throw SyncError.badResponse }
        return t
    }

    private func tokenRequest(_ form: [String: String]) async throws {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = form.map { "\($0.key)=\(Self.formEncode($0.value))" }.joined(separator: "&").data(using: .utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw SyncError.badResponse }
        guard http.statusCode == 200, let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = obj["access_token"] as? String else {
            let msg = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error_description"] as? String
            throw SyncError.http(http.statusCode, msg ?? String(decoding: data.prefix(200), as: UTF8.self))
        }
        accessToken = token
        expiry = Date().addingTimeInterval((obj["expires_in"] as? Double) ?? 3000)
        if let r = obj["refresh_token"] as? String { Keychain.set(r, for: Self.refreshKey) }
    }

    // MARK: Yardımcılar

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
        }
    }

    private static func randomString(_ n: Int) -> String {
        let chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return String((0..<n).map { _ in chars.randomElement()! })
    }
    private static func base64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    private static func formEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? s
    }
}

/// Drive v3, yalnızca appDataFolder (gizli uygulama alanı).
struct DriveClient {
    let auth: GoogleAuth

    private func send(_ build: (String) -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        for attempt in 0..<2 {
            let token = try await auth.validAccessToken(forceRefresh: attempt == 1)
            let (data, resp) = try await URLSession.shared.data(for: build(token))
            guard let http = resp as? HTTPURLResponse else { throw SyncError.badResponse }
            if http.statusCode == 401 && attempt == 0 { continue }
            guard (200..<300).contains(http.statusCode) else {
                let msg = (((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? [String: Any])?["message"] as? String
                throw SyncError.http(http.statusCode, msg ?? String(decoding: data.prefix(200), as: UTF8.self))
            }
            return (data, http)
        }
        throw SyncError.badResponse
    }

    /// Dosya adı → kimlik (aynı adlı birden fazla varsa en yeni olan).
    func listFiles() async throws -> [String: String] {
        var out: [String: String] = [:]
        var pageToken: String?
        repeat {
            var comps = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            comps.queryItems = [
                .init(name: "spaces", value: "appDataFolder"),
                .init(name: "fields", value: "nextPageToken,files(id,name,modifiedTime)"),
                .init(name: "orderBy", value: "modifiedTime desc"),
                .init(name: "pageSize", value: "1000"),
            ]
            if let pageToken { comps.queryItems?.append(.init(name: "pageToken", value: pageToken)) }
            let (data, _) = try await send { t in
                var r = URLRequest(url: comps.url!)
                r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
                return r
            }
            guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let files = obj["files"] as? [[String: Any]] else { throw SyncError.badResponse }
            for f in files {
                if let n = f["name"] as? String, let id = f["id"] as? String, out[n] == nil { out[n] = id }
            }
            pageToken = obj["nextPageToken"] as? String
        } while pageToken != nil
        return out
    }

    func download(id: String) async throws -> Data {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(id)?alt=media")!
        let (data, _) = try await send { t in
            var r = URLRequest(url: url)
            r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
            return r
        }
        return data
    }

    func upload(name: String, existingID: String?, data: Data) async throws {
        if let id = existingID {
            let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(id)?uploadType=media")!
            _ = try await send { t in
                var r = URLRequest(url: url)
                r.httpMethod = "PATCH"
                r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
                r.setValue("application/json", forHTTPHeaderField: "Content-Type")
                r.httpBody = data
                return r
            }
        } else {
            let boundary = "reader-\(UUID().uuidString)"
            var body = Data()
            let meta = "{\"name\":\"\(name)\",\"parents\":[\"appDataFolder\"]}"
            body.append("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n\(meta)\r\n".data(using: .utf8)!)
            body.append("--\(boundary)\r\nContent-Type: application/json\r\n\r\n".data(using: .utf8)!)
            body.append(data)
            body.append("\r\n--\(boundary)--".data(using: .utf8)!)
            let url = URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!
            _ = try await send { t in
                var r = URLRequest(url: url)
                r.httpMethod = "POST"
                r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
                r.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
                r.httpBody = body
                return r
            }
        }
    }

    // MARK: Büyük dosyalar (kitaplar)

    func delete(id: String) async throws {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(id)")!
        _ = try await send { t in
            var r = URLRequest(url: url)
            r.httpMethod = "DELETE"
            r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
            return r
        }
    }

    /// Dosyayı diske indirir ve geçici yolunu verir (çağıran taşır/siler).
    func downloadFile(id: String) async throws -> URL {
        let url = URL(string: "https://www.googleapis.com/drive/v3/files/\(id)?alt=media")!
        for attempt in 0..<2 {
            let token = try await auth.validAccessToken(forceRefresh: attempt == 1)
            var r = URLRequest(url: url)
            r.timeoutInterval = 600
            r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (tmp, resp) = try await URLSession.shared.download(for: r)
            guard let http = resp as? HTTPURLResponse else { throw SyncError.badResponse }
            if http.statusCode == 401 && attempt == 0 { continue }
            guard (200..<300).contains(http.statusCode) else {
                throw SyncError.http(http.statusCode, "Dosya indirilemedi")
            }
            let keep = FileManager.default.temporaryDirectory.appendingPathComponent("dl-\(UUID().uuidString)")
            try FileManager.default.moveItem(at: tmp, to: keep)
            return keep
        }
        throw SyncError.badResponse
    }

    /// Devam ettirilebilir (resumable) yükleme; dosya diskten akıtılır.
    func uploadFile(name: String, existingID: String?, file: URL, mime: String) async throws {
        let start: URL
        var meta = Data("{}".utf8)
        let method: String
        if let id = existingID {
            start = URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(id)?uploadType=resumable")!
            method = "PATCH"
        } else {
            start = URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")!
            method = "POST"
            meta = try JSONSerialization.data(withJSONObject: ["name": name, "parents": ["appDataFolder"]])
        }
        let (_, resp) = try await send { t in
            var r = URLRequest(url: start)
            r.httpMethod = method
            r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
            r.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            r.setValue(mime, forHTTPHeaderField: "X-Upload-Content-Type")
            r.httpBody = meta
            return r
        }
        guard let loc = resp.value(forHTTPHeaderField: "Location"), let upURL = URL(string: loc) else { throw SyncError.badResponse }
        var put = URLRequest(url: upURL)
        put.httpMethod = "PUT"
        put.timeoutInterval = 900
        put.setValue(mime, forHTTPHeaderField: "Content-Type")
        let (data, r2) = try await URLSession.shared.upload(for: put, fromFile: file)
        guard let http = r2 as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw SyncError.http((r2 as? HTTPURLResponse)?.statusCode ?? 0, String(decoding: data.prefix(200), as: UTF8.self))
        }
    }
}
