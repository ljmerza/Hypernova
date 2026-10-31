package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links in the chat (data/Links.kt): which addresses are found, trimmed, and where a tap goes. */
class LinksTest {
    private fun urls(text: String) = Links.find(text).map { it.url }

    @Test fun bareAddressesInProse() {
        val t = "The repo now exists at https://github.com/secondlife/webcorelib, with a worktree."
        val f = Links.find(t)
        assertEquals(listOf("https://github.com/secondlife/webcorelib"), f.map { it.url })
        assertEquals("https://github.com/secondlife/webcorelib", t.substring(f[0].range))
        assertEquals(listOf("http://a.org/x?y=1&z=2#top"), urls("see http://a.org/x?y=1&z=2#top."))
        assertEquals(listOf("https://a.org", "https://b.org/c"), urls("https://a.org and https://b.org/c;"))
        assertEquals(listOf("www.example.com/path"), urls("Try www.example.com/path!"))
        assertTrue(urls("no links here, just text.").isEmpty())
        // Glued to an address or a path, or nothing after the scheme: not one.
        assertTrue(urls("me@www.example.com").isEmpty())
        assertTrue(urls("look at https:// soon").isEmpty())
        assertTrue(urls("www. alone").isEmpty())
    }

    @Test fun trailingPunctuationAndUnbalancedBrackets() {
        assertEquals("https://a.org/x", Links.trim("https://a.org/x.,);:"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", Links.trim("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals(listOf("https://a.org/x"), urls("(see https://a.org/x)"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), urls("(https://en.wikipedia.org/wiki/Foo_(bar))."))
        assertEquals(listOf("https://a.org/x"), urls("**https://a.org/x**"))
        assertEquals(listOf("https://a.org/x"), urls("\"https://a.org/x\""))
        assertEquals(listOf("https://a.org/x"), urls("[https://a.org/x]"))
    }

    @Test fun onlyTheWebOpensAndDevServersPreview() {
        assertEquals(Links.Target.Web("https://github.com/x"), Links.target("https://github.com/x"))
        assertEquals(Links.Target.Web("http://a.org"), Links.target("http://a.org"))
        assertEquals(Links.Target.Web("https://www.example.com"), Links.target("www.example.com"))
        assertEquals(Links.Target.Web("HTTPS://A.ORG"), Links.target("HTTPS://A.ORG"))
        for (bad in listOf("intent://x#Intent;end", "file:///etc/passwd", "javascript:alert(1)", "content://media/1", "ftp://a.org", "mailto:a@b.c", "a.org", "https://", "https://a.org:99999/", "https://:80/")) {
            assertEquals(bad, Links.Target.Never, Links.target(bad))
        }
    }

    @Test fun localAddressesAreThePreviewButNeverHypernovasOwn() {
        assertEquals(Links.Target.Local(5173), Links.target("http://localhost:5173/"))
        assertEquals(Links.Target.Local(3000), Links.target("http://127.0.0.1:3000/app"))
        assertEquals(Links.Target.Local(8080), Links.target("http://0.0.0.0:8080"))
        assertEquals(Links.Target.Local(5173), Links.target("http://[::1]:5173/"))
        assertEquals(Links.Target.Local(80), Links.target("http://localhost/"))
        assertEquals(Links.Target.Local(443), Links.target("https://LOCALHOST"))
        for (own in 47321..47323) assertEquals(Links.Target.Never, Links.target("http://localhost:$own/qa"))
    }

    @Test fun theConfirmShowsTheRealHost() {
        val p = Links.parts("https://github.com@evil.example/login?x=1")!!
        assertEquals("https://", p.scheme)
        assertEquals("github.com@", p.userInfo)
        assertEquals("evil.example", p.host)
        assertEquals("/login?x=1", p.rest)
        // A link to evil.example, not github.com.
        assertEquals(Links.Target.Web("https://github.com@evil.example/login?x=1"), Links.target("https://github.com@evil.example/login?x=1"))
        assertEquals(Links.Parts("http://", "", "a.org:8080", ""), Links.parts("http://a.org:8080"))
        assertNull(Links.parts("javascript:alert(1)"))
    }

    @Test fun markdownLinks() {
        val s = "Read [the docs](https://a.org/docs) first."
        val m = Links.markdownLink(s, s.indexOf('['))!!
        assertEquals("the docs", m.label)
        assertEquals("https://a.org/docs", m.url)
        assertEquals(" first.", s.substring(m.end))
        assertNull(Links.markdownLink("[not a link] (x)", 0))
        assertNull(Links.markdownLink("[a](b c)", 0))
        assertNull(Links.markdownLink("x[a](b)", 0))
        // Found, but it never opens: the label alone is drawn (Transcript.kt, markdownLite).
        assertEquals(Links.Target.Never, Links.target(Links.markdownLink("[x](javascript:alert(1))", 0)?.url ?: "javascript:"))
    }
}
