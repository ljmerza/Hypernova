package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** A folder in the folder picker: its name, and its full path on the computer. */
@Immutable
data class Folder(val name: String, val path: String)

/**
 * A folder's own folders (`GET /v1/folders`, docs/PROTOCOL.md "Folders"): [path] as the computer
 * spells it, [parent] null at `/`, [home] where `~` is. [truncated]: there are [total], only the
 * first 2,000 here.
 */
@Immutable
data class Folders(val path: String, val parent: String?, val home: String, val folders: List<Folder>, val truncated: Boolean = false, val total: Int = folders.size)

/**
 * The new-session sheet's folder browser, without the screen (owner, 2026-10-29: "an easy mobile way
 * to navigate folders, or type in a manual path and select that one"): the path field read as a
 * folder and the start of a name in it, the crumbs, and the folder "Use this folder" means. Pure,
 * unit-tested.
 */
object FolderPick {
    fun wire(o: JSONObject): Folders {
        val path = o.str("path") ?: "/"
        val folders = o.arr("folders").mapObjects { f -> Folder(f.str("name") ?: return@mapObjects null, f.str("path") ?: return@mapObjects null) }
        return Folders(path, o.str("parent"), o.str("home") ?: "", folders, o.bool("truncated"), o.int("total") ?: folders.size)
    }

    /** [path] with the home folder ([home]) as `~`; anything else as it is. */
    fun tilde(path: String, home: String?): String {
        val h = home?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
        return when {
            path == h -> "~"
            path.startsWith("$h/") -> "~" + path.substring(h.length)
            else -> path
        }
    }

    /** How the field shows a folder: `~`-shortened, with a slash at the end, ready for a name in it. */
    fun field(path: String, home: String?): String = tilde(path, home).trimEnd('/') + "/"

    /**
     * What's typed, as the folder to list and the start of a name in it: `~/Prog` is (`~`, `Prog`),
     * `/media/` is (`/media`, ""), `~` is (`~`, ""). Nothing typed: the home folder.
     */
    fun split(typed: String): Pair<String, String> {
        val t = typed.trim()
        if (t.isEmpty() || t == "~") return "~" to ""
        if (t.endsWith("/")) return (t.trimEnd('/').ifEmpty { "/" }) to ""
        val at = t.lastIndexOf('/')
        if (at < 0) return t to "" // not a path: the computer says so
        return (t.substring(0, at).ifEmpty { "/" }) to t.substring(at + 1)
    }

    /** The folders [start] finds: those whose names start with it first, then those that hold it, their case ignored. */
    fun matching(folders: List<Folder>, start: String): List<Folder> {
        if (start.isEmpty()) return folders
        val first = folders.filter { it.name.startsWith(start, ignoreCase = true) }
        return first + folders.filter { it !in first && it.name.contains(start, ignoreCase = true) }
    }

    /** The folder "Use this folder" means: the one listed when nothing more is typed, else one of exactly that name in it, else none. */
    fun chosen(listed: Folders, start: String): String? =
        if (start.isEmpty()) listed.path else listed.folders.firstOrNull { it.name == start }?.path

    /**
     * The crumbs above the list, from the top: `~` and the folders under it in the home folder ([home]),
     * else `/` and each folder from the disk's top. Each with the path it opens.
     */
    fun crumbs(path: String, home: String?): List<Pair<String, String>> {
        val h = home?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        val inHome = h != null && (path == h || path.startsWith("$h/"))
        val (top, topPath, rest) = if (inHome) Triple("~", h, path.substring(h.length)) else Triple("/", "/", path)
        val out = arrayListOf(top to topPath)
        var at = topPath.trimEnd('/')
        for (name in rest.split('/').filter { it.isNotEmpty() }) {
            at = "$at/$name"
            out += name to at
        }
        return out
    }

    /** A folder's name as the chip says it: its last part (`/` for the disk's top). */
    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
}
