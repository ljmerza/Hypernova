package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Incident
import com.prudnikweb.hypernova.data.IncidentList

/** The think-twice card's cases: the phone's own file, which the build copies in (desktop/build.gradle.kts). */
object DeskIncidents {
    /** A mode waiting on the card, and the case it shows. */
    class Asked(val mode: String, val incident: Incident)

    private val all: List<Incident> by lazy {
        runCatching {
            IncidentList.parse(checkNotNull(DeskIncidents::class.java.classLoader.getResourceAsStream("res/raw/incidents.json")).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }
    private var last: String? = null

    /** Never the one shown last, unless it is the only one. Null with no list to show (the file is damaged): the choice goes on rather than being blocked. */
    fun next(): Incident? = IncidentList.pick(all, last)?.also { last = it.id }
}
