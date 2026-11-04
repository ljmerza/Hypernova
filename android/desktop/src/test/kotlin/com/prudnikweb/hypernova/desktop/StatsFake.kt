package com.prudnikweb.hypernova.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** An invented `GET /v1/stats` answer in the wire's shape (docs/PROTOCOL.md, "Statistics"), [scale] times the work: for the desktop's tests and pictures. */
fun statsAnswer(range: String, scale: Int = 1, today: LocalDate = LocalDate.now()): JSONObject {
    val zone = ZoneId.systemDefault()
    fun at(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val days = JSONArray()
    val minute = 60_000L
    val first = today.minusDays(29)
    var last30 = 0L
    for (i in 0 until 30) {
        val ms = if (i % 6 == 5) 0L else (35L + (i * 53L) % 340L) * minute * scale
        last30 += ms
        days.put(
            JSONObject().put("day", first.plusDays(i.toLong()).toString()).put("claudeMs", ms).put("codexMs", ms / 8).put("wallMs", ms * 3 / 4)
                .put("tokens", ms * 12).put("sessions", if (ms > 0) 3 else 0).put("prompts", if (ms > 0) 11 else 0),
        )
    }
    val share = when (range) {
        "today" -> 1
        "week" -> 7
        else -> 30
    }
    val ms = last30 * share / 30
    val from = when (range) {
        "today" -> at(today)
        "week" -> at(today.minusDays(6))
        "month" -> at(first)
        "ytd" -> at(today.withDayOfYear(1))
        else -> at(first)
    }
    val projects = JSONArray()
    for ((i, name) in listOf("ledgerline", "quillpad", "orchard-sync", "parcelkit").withIndex()) {
        projects.put(JSONObject().put("name", name).put("path", "/home/dev/$name").put("claudeMs", ms / (i + 2)).put("tokens", ms * 4 / (i + 1)).put("sessions", 9 - i * 2))
    }
    val tools = JSONArray()
    for ((name, n) in listOf("Bash" to 840, "Read" to 610, "Edit" to 320, "Grep" to 210, "Other" to 60)) tools.put(JSONObject().put("name", name).put("count", n * scale))
    return JSONObject()
        .put("range", range).put("from", from).put("to", System.currentTimeMillis()).put("computedAt", System.currentTimeMillis())
        .put("since", at(first)).put("countingSince", at(first))
        .put("claudeMs", ms).put("wallMs", ms * 3 / 4).put("agentMs", ms / 5)
        .put("tokens", JSONObject().put("input", ms / 3).put("output", ms / 2).put("cacheRead", ms * 9).put("cacheWrite", ms))
        .put("sessions", 24 * scale).put("prompts", 96 * scale).put("promptsFrom", JSONObject().put("phone", 21 * scale).put("voice", 4 * scale))
        .put("toolCalls", 2040 * scale).put("tools", tools).put("filesEdited", 118 * scale).put("agents", 12 * scale)
        .put("approvals", JSONObject().put("total", 34 * scale).put("phone", 19 * scale).put("mac", 15 * scale))
        .put("limitsHit", JSONObject().put("fiveHour", 2 * scale).put("sevenDay", 0).put("model", 1))
        .put("busiestDay", JSONObject().put("day", today.minusDays(4).toString()).put("claudeMs", 340 * minute * scale).put("tokens", 200_000_000L * scale))
        .put("longestSession", JSONObject().put("id", "s1").put("title", "Stream invoice exports instead of buffering").put("project", "ledgerline").put("claudeMs", 95 * minute).put("startedAt", at(today.minusDays(2))))
        .put("streak", 4).put("projects", projects).put("days", days)
        .put("byAgent", JSONObject().put("claude", JSONObject().put("ms", ms * 7 / 8).put("tokens", ms * 9).put("sessions", 20).put("prompts", 80).put("toolCalls", 1800)).put("codex", JSONObject().put("ms", ms / 8).put("tokens", ms).put("sessions", 4).put("prompts", 16).put("toolCalls", 240)))
}
