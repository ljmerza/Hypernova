package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import org.w3c.dom.Element
import java.io.InputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's strings, read from the phone's own res/values files, which the build puts in this app's
 * resources (desktop/build.gradle.kts), with this app's own words beside them. So there's one set of
 * words, and a key with no text fails a test (DesktopStringsTest) rather than a user.
 */
object DesktopStrings : Strings {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val PHONE = Regex("""\b(this|This|the|The|your|Your) phone\b""")
    private val SCAN = Regex("""\bscan the (new )?code\b""")

    init {
        val loader = DesktopStrings::class.java.classLoader
        fun open(name: String): InputStream = checkNotNull(loader.getResourceAsStream(name)) { "no resource $name" }
        val index = open("res/values/index.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        for (name in index) read(open("res/values/$name"), ::forDesktop)
        // Last, so one of this app's own is never the phone's.
        read(open("desktop/strings_desktop.xml")) { it }
    }

    private fun read(from: InputStream, words: (String) -> String) {
        val builder = DocumentBuilderFactory.newInstance().apply {
            // Our own files, but an XML parser needn't fetch anything for them either.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val root = from.use { builder.parse(it) }.documentElement
        val items = root.childNodes
        for (i in 0 until items.length) {
            val e = items.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = words(decode(e.textContent))
                "plurals" -> {
                    val q = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until q.length).map { q.item(it) as Element }
                        .associate { it.getAttribute("quantity") to words(decode(it.textContent)) }
                }
            }
        }
    }

    /** A resource string's text as aapt compiles it: its escapes done, spaces collapsed outside double quotes (as the tests' XmlStrings). */
    fun decode(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    if (space) { if (out.isNotEmpty()) out.append(' '); space = false }
                    i++
                    when (val n = raw[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> { out.append(raw.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> out.append(n)
                    }
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> space = true
                else -> {
                    if (space) { if (out.isNotEmpty()) out.append(' '); space = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }


    /** The phone's words where they say what this isn't: "this phone" is this computer, and nothing here scans a code. */
    fun forDesktop(text: String): String {
        val noPhone = PHONE.replace(text) { m -> if (m.groupValues[1].first().isUpperCase()) "This computer" else "this computer" }
        return SCAN.replace(noPhone) { m -> "paste the ${m.groupValues[1]}link" }
    }

    /** A string by its resource name, for this app's screens ([Ui]) as for [Str]. */
    fun named(name: String): String = checkNotNull(strings[name]) { "no string $name" }

    fun has(name: String): Boolean = name in strings

    fun quantityNamed(name: String, quantity: Int): String {
        val q = checkNotNull(plurals[name]) { "no plurals $name" }
        return checkNotNull(q[if (quantity == 1) "one" else "other"] ?: q["other"]) { "no quantity for $name" }
    }

    fun hasPlural(name: String): Boolean = name in plurals

    override fun text(key: Str): String = named(key.name)

    override fun quantityText(key: Plural, quantity: Int): String = quantityNamed(key.name, quantity)

    override val locale: Locale get() = Locale.getDefault()

    /** This computer's own clock style, from its locale: there's no 12 or 24 hour setting a JVM can read. */
    override fun timePattern(withDay: Boolean): String {
        val time = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern() ?: "HH:mm"
        return if (withDay) "EEE $time" else time
    }
}

/**
 * The words the screens use, by resource name. The names that are the phone's (res/values) and the ones
 * that are this app's alone (resources/desktop/strings_desktop.xml) are all here, so a test can check each
 * has its text, and a rename of the phone's fails there.
 */
@Suppress("EnumEntryName")
enum class Ui {
    allow,
    deny,
    group_needs,
    group_working,
    group_done,
    state_working,
    state_needs_you,
    state_idle,
    state_starting,
    state_error,
    state_ended,
    state_mac_restarted,
    state_paused,
    state_signed_out,
    state_no_plan,
    state_on_hold,
    state_no_credit,
    widget_allowing,
    widget_denying,
    card_job_died,
    cant_reach,
    retry_now,
    retrying_in,
    retrying_now,
    connlog_route_local,
    connlog_route_base,
    connlog_result_refused,
    connlog_result_timeout,
    connlog_result_unreachable,
    connlog_result_no_name,
    connlog_result_pin_wrong,
    connlog_result_pin_expired,
    connlog_result_tls,
    connlog_result_http,
    connlog_result_closed,
    connlog_result_failed,
    connlog_result_ok,
    connlog_result_skipped,
    dur_s,
    dur_m,
    dur_hm,
    dur_dh,
    onb_title,
    onb_title_add,
    onb_paste_hint,
    onb_pair,
    pair_working,
    pair_failed,
    pair_expired,
    pair_closed,
    pair_too_many,
    pair_bad_link,
    pair_back,
    pair_label,
    desk_pair_help,
    desk_pair_field,
    desk_pair_too_long,
    desk_bad_link_body,
    desk_expired_body,
    desk_closed_body,
    desk_too_many_body,
    desk_pair_another,
    desk_link_linked,
    desk_link_connecting,
    desk_link_reconnecting,
    desk_link_offline,
    desk_link_unpaired,
    desk_stale,
    desk_unpaired_title,
    desk_unpaired_body,
    desk_pair_again,
    desk_no_sessions,
    desk_no_sessions_body,
    desk_answer_on_phone_question,
    desk_answer_on_phone_plan,
    desk_not_whole,
    desk_answer_failed,
    desk_from_agent,
    desk_pane_none,
    desk_pane_gone,
    desk_pane_gone_body,
    desk_pane_phone_only,
    desk_pane_history_only,
    desk_pane_runs_phone,
    desk_more_asks,
    desk_zoom,
    desk_zoom_auto,
    desk_settings,
    desk_open_folder,
    desk_folder_failed,
    desk_folder_copied,
    desk_alert_body,
    desk_alerts_title,
    desk_alerts_tray,
    desk_alerts_no_tray,
    desk_alerts_only_open,
    desk_key_title,
    desk_key_none,
    desk_key_keychain,
    desk_key_file_down,
    desk_key_file_no_helper,
    desk_key_file_did_not_hold,
    desk_key_file_off,
    desk_key_file_note,
    desk_token_keychain,
    desk_token_file_down,
    desk_token_file_no_helper,
    desk_token_file_did_not_hold,
    desk_token_file_off,
    desk_token_file_note,
    desk_token_retry,
    desk_token_retrying,
    desk_wait_title,
    desk_wait_locked,
    desk_wait_gone,
    desk_wait_no_helper,
    desk_wait_off,
    desk_wait_forget,
    desk_wait_forget_note,
    untitled_session,
    notif_tool,
    notif_tool_plain,
    notif_question,
    notif_question_plain,
    notif_plan,
    notif_plan_plain,
    notif_from_agent,
    notif_from_an_agent,
    notif_server_from_agent,
    notif_server_from_an_agent,
    notice_title_waiting,
    notice_title_needs_you,
    back,
    cancel,
    model,
    mode,
    mode_label_default,
    mode_label_accept,
    mode_label_plan,
    mode_label_auto,
    mode_label_bypass,
    mode_label_dont_ask,
    cx_mode_label_read_only,
    cx_mode_label_can_edit,
    claude,
    cx_agent_codex,
    agents_running,
    queued,
    st_top_bar_context,
    session_sheet_agents,
    session_sheet_agent,
    session_sheet_folder,
    session_sheet_runs_in,
    session_sheet_runs_terminal,
    session_sheet_runs_mac,
    session_sheet_runs_watched,
    session_sheet_started,
    session_sheet_running_for,
    session_sheet_ran_for,
    session_sheet_this_turn,
    session_sheet_last_activity,
    queued_label,
    meta_ago_now,
    sessions,
    history,
    history_search,
    history_today,
    history_yesterday,
    history_no_match,
    history_none_in,
    history_searching,
    history_partial,
    history_empty,
    history_trail,
    au_session_chip,
    au_history_deleted,
    close,
    session_rename,
    session_name,
    session_stop,
    session_remove,
    session_remove_short,
    session_delete_q,
    session_delete_body,
    session_delete_body_codex,
    session_delete_body_running,
    session_delete_body_terminal,
    session_delete_body_plain,
    session_delete_cant_q,
    session_delete_cant_body,
    err_rename,
    err_stop_session,
    err_delete_session,
    save,
    fl_ok,
    debuglog_title,
    debuglog_switch,
    debuglog_off,
    debuglog_on_until,
    debuglog_holds,
    debuglog_crash_note,
    debuglog_size,
    debuglog_size_crash,
    debuglog_none,
    debuglog_send,
    debuglog_clear,
    debuglog_send_to,
    debuglog_sending,
    debuglog_sent,
    debuglog_copy_path,
    debuglog_copied,
    debuglog_too_old,
    debuglog_send_failed,
    debuglog_clear_q,
    debuglog_clear_body,
    debuglog_cleared,
    ;

    /** This word, as the computer on screen words things ([ComputerWords]). */
    fun text(): String = words(DesktopStrings.named(name))

    fun format(vararg args: Any?): String = String.format(DesktopStrings.locale, text(), *args)

    private fun words(text: String): String =
        if (ComputerWords.platform == com.prudnikweb.hypernova.data.Mac.LINUX) ComputerWords.reword(text) else text
}
