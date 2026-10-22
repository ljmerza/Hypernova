package com.prudnikweb.hypernova.data

/**
 * The new-session sheet's project picker, without the screen: the projects found by a few typed
 * letters (name or path), and the list unsearched: the most recent first, then the rest by the
 * folder they live in (the projects folder, Nebula, a project's worktrees, any other folder). Pure,
 * unit-tested.
 */
object ProjectPick {
    /** How many of the most recently active projects lead the list. */
    const val RECENT = 5

    /** A part of the unsearched list. [label]: the folder, as [Group.kind] says how to word it. */
    data class Group(val kind: Kind, val label: String?, val projects: List<Project>)

    enum class Kind {
        /** The most recently active, newest first. */
        Recent,
        /** Directly in the projects folder ([Group.label]: it, as `~/Programming`). */
        Root,
        /** New sessions from the glasses (a folder named Nebula). */
        Nebula,
        /** A project's worktrees (`<project>/.claude/worktrees/…`; [Group.label]: the project's name). */
        Worktrees,
        /** Any other folder ([Group.label]: it, as `~/Documents/programming`). */
        Folder,
    }

    /** `/Users/me/Programming/x` (or `/home/me/…`) → `~/Programming/x`; anything else as it is. */
    fun tilde(path: String): String {
        val m = HOME.find(path) ?: return path
        return "~" + path.substring(m.value.length)
    }

    private val HOME = Regex("^(/Users/[^/]+|/home/[^/]+)(?=/|$)")

    /** The folder a project lives in, `~`-shortened, without a trailing slash. */
    fun parent(path: String): String = tilde(path).trimEnd('/').substringBeforeLast('/', "")

    private const val WORKTREES = "/.claude/worktrees/"

    /**
     * The list before anything is typed: the [RECENT] most recently active (that have been active at
     * all), then the rest in groups, each by name: the projects folder [root] first, then Nebula,
     * then the others, the most recently active group first.
     */
    fun groups(projects: List<Project>, root: String?): List<Group> {
        val recent = projects.filter { it.lastActivityAt != null }.sortedByDescending { it.lastActivityAt }.take(RECENT)
        val taken = recent.mapTo(HashSet()) { it.path }
        val rootT = root?.let { tilde(it).trimEnd('/') }
        val byKey = LinkedHashMap<Pair<Kind, String>, MutableList<Project>>()
        for (p in projects) {
            if (p.path in taken) continue
            val t = tilde(p.path)
            val parent = parent(p.path)
            val key = when {
                t.contains(WORKTREES) -> Kind.Worktrees to t.substringBefore(WORKTREES).substringAfterLast('/')
                rootT != null && parent == rootT -> Kind.Root to rootT
                parent.substringAfterLast('/') == "Nebula" -> Kind.Nebula to parent
                else -> Kind.Folder to parent.ifEmpty { "/" }
            }
            byKey.getOrPut(key) { ArrayList() } += p
        }
        val rank = { k: Kind -> when (k) { Kind.Root -> 0; Kind.Nebula -> 1; else -> 2 } }
        val latest = { list: List<Project> -> list.maxOfOrNull { it.lastActivityAt ?: 0L } ?: 0L }
        val rest = byKey.entries
            .sortedWith(compareBy<Map.Entry<Pair<Kind, String>, MutableList<Project>>> { rank(it.key.first) }.thenByDescending { latest(it.value) }.thenBy { it.key.second })
            .map { (k, list) -> Group(k.first, if (k.first == Kind.Nebula) null else k.second, list.sortedWith(compareBy<Project> { it.name.lowercase() }.thenBy { it.path })) }
        return (if (recent.isEmpty()) emptyList() else listOf(Group(Kind.Recent, null, recent))) + rest
    }

    /**
     * The projects [query] finds, best first: each of its words in the name (counting most) or the
     * `~`-shortened path; the more recently active first among equals. Empty query: none.
     */
    fun search(projects: List<Project>, query: String): List<Project> {
        val words = Fuzzy.words(query)
        if (words.isEmpty()) return emptyList()
        // Each project with its total and its weakest word's score (without the name's bonus).
        val scored = ArrayList<Triple<Project, Int, Int>>()
        for (p in projects) {
            val path = tilde(p.path)
            var total = 0
            var weakest = Int.MAX_VALUE
            var ok = true
            for (w in words) {
                val name = Fuzzy.word(w, p.name)
                val where = Fuzzy.word(w, path)
                val best = maxOf(name?.plus(NAME_BONUS) ?: -1, where ?: -1)
                if (best < 0) { ok = false; break }
                total += best
                weakest = minOf(weakest, maxOf(name ?: -1, where ?: -1))
            }
            if (ok) scored += Triple(p, total, weakest)
        }
        val sorted = scored.sortedWith(compareByDescending<Triple<Project, Int, Int>> { it.second }.thenByDescending { it.first.lastActivityAt ?: 0L }.thenBy { it.first.name.lowercase() })
        // Letters scattered through a path ("even" in "…/advent-of-code") only when nothing better matched.
        return Fuzzy.keepStrong(sorted.map { it.first to it.third })
    }

    /** A name found counts more than the same letters in the path. */
    private const val NAME_BONUS = 150

    /** What may be a new project's folder name (the daemon's rule): letters, digits, `.`, `_`, `-`; up to 64. */
    private val NAME_OK = Regex("^[A-Za-z0-9_][A-Za-z0-9._-]{0,63}$")

    fun validName(name: String): Boolean = NAME_OK.matches(name.trim())

    /** What's typed, as a folder name could be: spaces as dashes, nothing a folder name can't hold. */
    fun asName(typed: String): String = typed.trim().replace(Regex("\\s+"), "-").replace(Regex("[^A-Za-z0-9._-]"), "").take(64)

    /**
     * Which project the sheet starts on: the one it was opened for ([asked]: a path, or a name from
     * the sky's filter), else the last one a session started in ([last], a path) while it's still
     * there, else the most recently active; null when there are none.
     */
    fun initial(projects: List<Project>, asked: String?, last: String?): String? {
        if (asked != null) (projects.firstOrNull { it.path == asked } ?: projects.firstOrNull { it.name == asked })?.let { return it.path }
        if (last != null && projects.any { it.path == last }) return last
        return projects.maxByOrNull { it.lastActivityAt ?: Long.MIN_VALUE }?.path
    }
}
