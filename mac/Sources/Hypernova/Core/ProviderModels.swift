import Foundation

// Other providers' state and carrying on after a limit (docs/PROTOCOL.md, "Providers", "Account",
// "Auto-continue"). Every type here decodes leniently: an odd field from a newer daemon reads as
// absent and never sinks the provider or session it rides in.

/// A provider's trouble: "no_credit", "bad_key", "limited" or "down" (an unknown state shows as
/// "can't reply"). `resetsAt`: when a limit lifts, only when the provider said. `budget`: out of
/// credit under a cap someone set (the key's limit), not the account's balance.
struct ProviderStatus: Decodable, Equatable, Hashable {
    var state: String?
    var text: String?
    var since: FlexDate?
    var resetsAt: FlexDate?
    var budget: Bool?

    init(state: String?, text: String? = nil, resetsAt: FlexDate? = nil, budget: Bool? = nil) {
        self.state = state; self.text = text; self.resetsAt = resetsAt; self.budget = budget
    }
    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        state = try? c.decodeIfPresent(String.self, forKey: .state)
        text = try? c.decodeIfPresent(String.self, forKey: .text)
        since = try? c.decodeIfPresent(FlexDate.self, forKey: .since)
        resetsAt = try? c.decodeIfPresent(FlexDate.self, forKey: .resetsAt)
        budget = try? c.decodeIfPresent(Bool.self, forKey: .budget)
    }
    private enum K: String, CodingKey { case state, text, since, resetsAt, budget }

    /// It waits on you (credit, a key), not on time.
    var needsYou: Bool { state == "no_credit" || state == "bad_key" }

    /// "out of credits", "budget reached", "key refused", "rate limited", "not answering".
    var words: String {
        switch state {
        case "no_credit": return budget == true ? "budget reached" : "out of credits"
        case "bad_key": return "key refused"
        case "limited": return "rate limited"
        case "down": return "not answering"
        default: return "can’t reply"
        }
    }
}

/// What's left to spend on a provider, where its API says (OpenRouter): dollars under its key's
/// cap (nil: no cap, so the account's balance can't be told), spent today, free-model requests.
struct ProviderBalance: Decodable, Equatable, Hashable {
    var remaining: Double?
    var usedToday: Double?
    var freeLeft: Int?
    var freeLimit: Int?
    var checkedAt: FlexDate?
    /// The last read failed ("bad_key", "unreachable"): the numbers are the last good ones.
    var error: String?

    init(remaining: Double?, usedToday: Double? = nil) { self.remaining = remaining; self.usedToday = usedToday }
    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        remaining = try? c.decodeIfPresent(Double.self, forKey: .remaining)
        usedToday = try? c.decodeIfPresent(Double.self, forKey: .usedToday)
        freeLeft = try? c.decodeIfPresent(Int.self, forKey: .freeLeft)
        freeLimit = try? c.decodeIfPresent(Int.self, forKey: .freeLimit)
        checkedAt = try? c.decodeIfPresent(FlexDate.self, forKey: .checkedAt)
        error = try? c.decodeIfPresent(String.self, forKey: .error)
    }
    private enum K: String, CodingKey { case remaining, usedToday, freeLeft, freeLimit, checkedAt, error }

    /// Dollars as OpenRouter bills them, whatever the Mac's language: "$4.20".
    static func usd(_ v: Double) -> String { String(format: "$%.2f", locale: Locale(identifier: "en_US_POSIX"), max(0, v)) }

    /// What's left under the key's cap, when it may be shown beside [status]. The cap says nothing
    /// about the account's own credit: when the account ran out (no_credit, not a budget),
    /// "$3.00 left" beside "out of credits" would be untrue, so it isn't shown.
    func capLeft(under status: ProviderStatus?) -> Double? {
        if status?.state == "no_credit" && status?.budget != true { return nil }
        return remaining
    }

    /// "$4.20 left", "$1.10 today" (no cap), "37 of 50 free today"; nil when nothing's known.
    var words: String? { words(under: nil) }

    /// [words], beside the provider's trouble [status] (see [capLeft]).
    func words(under status: ProviderStatus?) -> String? {
        if let r = capLeft(under: status) { return "\(Self.usd(r)) left" }
        if let u = usedToday, u > 0 { return "\(Self.usd(u)) today" }
        if let l = freeLeft, let n = freeLimit, n > 0 { return "\(l) of \(n) free today" }
        if let u = usedToday { return "\(Self.usd(u)) today" }
        return nil
    }
}

/// Whether a session a limit stopped carries on by itself: "armed" (at `at`), "off" (back at
/// `at`, when known) or "ready" (it's over and it didn't: Continue). `by`: "claude" (Claude Code,
/// in its terminal) or "hypernova" (the daemon).
struct Resume: Decodable, Equatable, Hashable {
    var state: String?
    var at: FlexDate?
    var by: String?
    var canCancel: Bool?
    var canArm: Bool?
    var why: String?

    init(state: String?, at: FlexDate?, by: String? = "hypernova", canCancel: Bool? = nil, canArm: Bool? = nil, why: String? = nil) {
        self.state = state; self.at = at; self.by = by; self.canCancel = canCancel; self.canArm = canArm; self.why = why
    }
    init(from decoder: Decoder) throws {
        guard let c = try? decoder.container(keyedBy: K.self) else { return }
        state = try? c.decodeIfPresent(String.self, forKey: .state)
        at = try? c.decodeIfPresent(FlexDate.self, forKey: .at)
        by = try? c.decodeIfPresent(String.self, forKey: .by)
        canCancel = try? c.decodeIfPresent(Bool.self, forKey: .canCancel)
        canArm = try? c.decodeIfPresent(Bool.self, forKey: .canArm)
        why = try? c.decodeIfPresent(String.self, forKey: .why)
    }
    private enum K: String, CodingKey { case state, at, by, canCancel, canArm, why }

    var armed: Bool { state == "armed" }
    var off: Bool { state == "off" }
    var ready: Bool { state == "ready" }
}

extension DaemonClient {
    /// Add ([id] nil) or edit a provider; the answer comes after a new key or address was checked.
    func saveProvider(_ id: String?, _ p: ProviderInput) async throws -> ProviderEnvelope {
        if let id { return try await request("PUT", "/v1/providers/\(Self.segment(id))", body: p, timeout: 20, as: ProviderEnvelope.self) }
        return try await request("POST", "/v1/providers", body: p, timeout: 20, as: ProviderEnvelope.self)
    }
    /// Check an address and a key without saving them ([id]: an existing provider, whose saved key
    /// is used when none is given).
    func checkProviderInput(_ p: ProviderInput, id: String?) async throws -> ProviderCheck? {
        struct Body: Encodable { var kind: String; var baseUrl: String?; var apiKey: String?; var name: String; var id: String? }
        return try await request("POST", "/v1/providers/check", body: Body(kind: p.kind, baseUrl: p.baseUrl, apiKey: p.apiKey, name: p.name, id: id),
                                 timeout: 20, as: ProviderCheckEnvelope.self).check
    }
    /// Read a provider's balance now (OpenRouter; at most once in 10 s on the daemon's side).
    func checkProvider(_ id: String) async throws -> Provider? {
        try await request("POST", "/v1/providers/\(Self.segment(id))/check", timeout: 12, as: ProviderEnvelope.self).provider
    }
    /// Turn a blocked session's carrying on by itself on or off (Claude Code's in its terminal: Esc
    /// or its /rate-limit-options menu, which can take a few seconds).
    func autoContinue(_ id: String, on: Bool) async throws {
        _ = try await request("POST", "/v1/sessions/\(Self.segment(id))/auto-continue", body: ["on": on], timeout: 20, as: EmptyResponse.self)
    }
    /// Carry a session on after a limit it didn't continue from by itself.
    func continueAfterLimit(_ id: String) async throws {
        _ = try await request("POST", "/v1/sessions/\(Self.segment(id))/continue", body: ["reason": "limit"], timeout: 15, as: EmptyResponse.self)
    }
}
