package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The new-session sheet's folder browser: the path field, the crumbs, the folder Use means (data/FolderPick.kt). */
class FolderPickTest {
    private val home = "/home/cubxi"
    private fun f(dir: String, vararg names: String) = Folders(dir, dir.substringBeforeLast('/', "").ifEmpty { "/" }, home, names.map { Folder(it, "$dir/$it") })

    @Test fun theFieldIsAFolderAndTheStartOfANameInIt() {
        assertEquals("~" to "", FolderPick.split(""))
        assertEquals("~" to "", FolderPick.split("~"))
        assertEquals("~" to "", FolderPick.split("~/"))
        assertEquals("~" to "Prog", FolderPick.split("~/Prog"))
        assertEquals("/media/cubxi" to "docker", FolderPick.split("/media/cubxi/docker"))
        assertEquals("/media/cubxi/docker" to "", FolderPick.split("/media/cubxi/docker/"))
        assertEquals("/" to "", FolderPick.split("/"))
        assertEquals("/" to "med", FolderPick.split("/med"))
        // Not a path: asked as it is, and the computer says so.
        assertEquals("projects" to "", FolderPick.split("projects"))
    }

    @Test fun theFieldShowsTheHomeFolderAsTilde() {
        assertEquals("~/", FolderPick.field(home, home))
        assertEquals("~/Programming/", FolderPick.field("$home/Programming", home))
        assertEquals("/media/cubxi/docker/", FolderPick.field("/media/cubxi/docker", home))
        assertEquals("/", FolderPick.field("/", home))
        // A folder that only starts like the home folder isn't in it.
        assertEquals("/home/cubxi2", FolderPick.tilde("/home/cubxi2", home))
        assertEquals("/home/cubxi", FolderPick.tilde("/home/cubxi", null))
    }

    @Test fun crumbsStartAtTildeInTheHomeFolderElseAtTheTop() {
        assertEquals(listOf("~" to home, "work" to "$home/work", "site" to "$home/work/site"), FolderPick.crumbs("$home/work/site", home))
        assertEquals(listOf("~" to home), FolderPick.crumbs(home, home))
        assertEquals(listOf("/" to "/", "media" to "/media", "cubxi" to "/media/cubxi", "docker" to "/media/cubxi/docker"), FolderPick.crumbs("/media/cubxi/docker", home))
        assertEquals(listOf("/" to "/"), FolderPick.crumbs("/", home))
    }

    @Test fun aTypedNameFindsFoldersThatStartWithItFirst() {
        val docker = f("/media/cubxi/docker", "bin", "env", "projects", "pokemon", "volumes", "deploy-env")
        assertEquals(docker.folders, FolderPick.matching(docker.folders, ""))
        assertEquals(listOf("projects", "pokemon", "deploy-env"), FolderPick.matching(docker.folders, "P").map { it.name })
        assertEquals(listOf("env", "deploy-env"), FolderPick.matching(docker.folders, "env").map { it.name })
        assertEquals(emptyList<Folder>(), FolderPick.matching(docker.folders, "zzz"))
    }

    @Test fun useMeansTheFolderListedOrOneWhoseWholeNameIsTyped() {
        val docker = f("/media/cubxi/docker", "bin", "projects")
        assertEquals("/media/cubxi/docker", FolderPick.chosen(docker, ""))
        assertEquals("/media/cubxi/docker/projects", FolderPick.chosen(docker, "projects"))
        assertNull(FolderPick.chosen(docker, "proj"))
        assertEquals("docker", FolderPick.name("/media/cubxi/docker/"))
        assertEquals("/", FolderPick.name("/"))
    }

    @Test fun theDaemonsAnswerIsRead() {
        val o = JSONObject(
            """{"path":"/media/cubxi/docker","parent":"/media/cubxi","home":"/home/cubxi","folders":[{"name":"bin","path":"/media/cubxi/docker/bin"},{"name":"broken"}],"truncated":false,"total":1}""",
        )
        val got = FolderPick.wire(o)
        assertEquals("/media/cubxi/docker", got.path)
        assertEquals("/media/cubxi", got.parent)
        assertEquals("/home/cubxi", got.home)
        assertEquals(listOf(Folder("bin", "/media/cubxi/docker/bin")), got.folders)
        assertNull(FolderPick.wire(JSONObject("""{"path":"/","parent":null,"home":"/root","folders":[]}""")).parent)
    }
}
