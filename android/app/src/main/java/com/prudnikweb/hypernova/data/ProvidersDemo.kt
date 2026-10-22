package com.prudnikweb.hypernova.data

import android.content.res.Resources

/**
 * The demo's provider checks (FakeHub), so the editor's verdicts can be seen without a Mac: a key
 * with "bad" in it is refused, "empty" is out of credits, "slow" is rate limited, a custom address
 * with "nothing" in it doesn't answer; anything else works ("$4.20 left" on OpenRouter). Plain http
 * to the internet and a key with a space are refused as the Mac refuses them.
 */
object DemoProviders {
    fun refusal(res: Resources, kind: String, baseUrl: String?, apiKey: String?): ProviderRefused? {
        val url = baseUrl.orEmpty().trim()
        if (kind == "custom" && url.isEmpty()) return ProviderRefused("missing_url", res.getString(com.prudnikweb.hypernova.R.string.provider_needs_url))
        if (ProviderForm.insecure(url)) return ProviderRefused("insecure_url", "The key would travel unencrypted. Use the https:// address.")
        if (url.contains('@')) return ProviderRefused("bad_url", "Put the key in its own field, not in the address.")
        if (apiKey != null && apiKey.trim().any { it.code !in 0x21..0x7e }) return ProviderRefused("bad_key_format", "That key has characters a key can’t have. Copy it again.")
        return null
    }

    fun check(kind: String, key: String, baseUrl: String? = null): ProviderCheck {
        val now = System.currentTimeMillis()
        val k = key.lowercase()
        val name = if (kind == "openrouter") "OpenRouter" else "The gateway"
        return when {
            k.isBlank() -> ProviderCheck(ProviderCheck.BAD_KEY, "$name has no key to check.", null, null, now)
            "bad" in k -> ProviderCheck(ProviderCheck.BAD_KEY, "$name refused this key.", null, null, now)
            "empty" in k -> ProviderCheck(ProviderCheck.NO_CREDIT, "The key works, but the account is out of credits.", null, Balance(0.0, 0.0, 0, 50, now, null), now)
            "slow" in k -> ProviderCheck(ProviderCheck.LIMITED, "Rate limited just now. Try again in a minute.", null, null, now)
            baseUrl?.contains("nothing") == true -> ProviderCheck(ProviderCheck.UNREACHABLE, "Nothing answers at this address.", null, null, now)
            kind == "openrouter" -> ProviderCheck(ProviderCheck.OK, "Key works. $4.20 left.", null, Balance(4.2, 0.35, 37, 50, now, null), now)
            else -> ProviderCheck(ProviderCheck.OK, "Key works. 3 models.", listOf("gpt-5", "gemini-3-pro", "qwen3-coder"), null, now)
        }
    }
}
