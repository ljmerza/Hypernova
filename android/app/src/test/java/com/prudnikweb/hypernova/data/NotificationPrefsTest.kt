package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which notifications this phone posts (data/NotificationPrefs.kt): the rule, the map of kinds, the confirm's test, what's kept. */
class NotificationPrefsTest {
    private class Mem : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }
    }

    private val every = Notice.Kind.entries

    @Test fun everythingIsOnByDefaultForAnyServerAndForNone() {
        val p = NotificationPrefs()
        for (k in every) {
            assertTrue("$k", p.allows("z2", k))
            assertTrue("$k", p.allows(null, k))
        }
    }

    @Test fun eachKindIsInTheGroupTheOwnerChose() {
        val want = mapOf(
            Notice.Kind.Approval to NotifyGroup.Answers,
            Notice.Kind.Question to NotifyGroup.Answers,
            Notice.Kind.Plan to NotifyGroup.Answers,
            Notice.Kind.Finished to NotifyGroup.Finished,
            Notice.Kind.Waiting to NotifyGroup.Waiting,
            Notice.Kind.Error to NotifyGroup.Errors,
            Notice.Kind.Limit to NotifyGroup.Limits,
            Notice.Kind.Account to NotifyGroup.Limits,
            Notice.Kind.Power to NotifyGroup.Limits,
            Notice.Kind.Backpack to NotifyGroup.Limits,
            Notice.Kind.Automation to NotifyGroup.Automations,
            Notice.Kind.Update to NotifyGroup.Updates,
            Notice.Kind.ServerUpdateFailed to NotifyGroup.Updates,
        )
        assertEquals("every kind is mapped here", every.toSet(), want.keys)
        for ((k, g) in want) assertEquals("$k", g, NotifyGroup.of(k))
    }

    @Test fun aDefaultTurnedOffSilencesItsKindsAlone() {
        val p = NotificationPrefs().withDefault(NotifyGroup.Updates, on = false)
        assertFalse(p.allows("z2", Notice.Kind.Update))
        assertFalse(p.allows("z2", Notice.Kind.ServerUpdateFailed))
        assertTrue(p.allows("z2", Notice.Kind.Finished))
        assertTrue("back on", p.withDefault(NotifyGroup.Updates, on = true).allows("z2", Notice.Kind.Update))
    }

    @Test fun sameAsAllFollowsTheDefaultsAsTheyChange() {
        val p = NotificationPrefs().withMode("z2", ServerNotify.Same)
        assertTrue(p.allows("z2", Notice.Kind.Finished))
        assertFalse(p.withDefault(NotifyGroup.Finished, on = false).allows("z2", Notice.Kind.Finished))
    }

    @Test fun customWinsOverTheDefaultsForThatServerOnly() {
        val p = NotificationPrefs()
            .withDefault(NotifyGroup.Finished, on = false)
            .withMode("z2", ServerNotify.Custom)
            // Custom starts from the defaults: nothing changes by choosing it.
            .also { assertFalse(it.allows("z2", Notice.Kind.Finished)) }
            .withCustom("z2", NotifyGroup.Finished, on = true)
            .withCustom("z2", NotifyGroup.Errors, on = false)
        assertTrue(p.allows("z2", Notice.Kind.Finished))
        assertFalse(p.allows("z2", Notice.Kind.Error))
        assertFalse("another follows the defaults", p.allows("gym", Notice.Kind.Finished))
        assertTrue(p.allows("gym", Notice.Kind.Error))
        // The defaults moving later don't reach a Custom server.
        assertTrue(p.withDefault(NotifyGroup.Updates, on = false).allows("z2", Notice.Kind.Update))
    }

    @Test fun offSilencesEveryKindOfThatServerAndNoOther() {
        val p = NotificationPrefs().withMode("z2", ServerNotify.Off)
        for (k in every) {
            assertFalse("$k", p.allows("z2", k))
            assertTrue("$k", p.allows("gym", k))
        }
    }

    @Test fun masterOffIsNothingFromAnyServerWhateverItsOverride() {
        val custom = NotificationPrefs().withMode("z2", ServerNotify.Custom)
        val p = custom.copy(master = false)
        for (k in every) {
            assertFalse("$k", p.allows("z2", k))
            assertFalse("$k", p.allows("gym", k))
            assertFalse("$k", p.allows(null, k))
        }
        assertTrue("and back", custom.allows("z2", Notice.Kind.Approval))
    }

    @Test fun customComesBackAsItWasAfterSameAndOff() {
        val p = NotificationPrefs().withCustom("z2", NotifyGroup.Errors, on = false)
        assertEquals(ServerNotify.Custom, p.modeOf("z2"))
        val same = p.withMode("z2", ServerNotify.Same)
        assertEquals(ServerNotify.Same, same.modeOf("z2"))
        assertTrue("follows the defaults meanwhile", same.allows("z2", Notice.Kind.Error))
        val back = same.withMode("z2", ServerNotify.Off).withMode("z2", ServerNotify.Custom)
        assertEquals(setOf(NotifyGroup.Errors), back.customOf("z2"))
        assertFalse(back.allows("z2", Notice.Kind.Error))
    }

    @Test fun forgettingAServerRemovesItsOverride() {
        val p = NotificationPrefs().withMode("z2", ServerNotify.Off).withMode("gym", ServerNotify.Off).without("z2")
        assertEquals(setOf("gym"), p.servers.keys)
        assertTrue(p.allows("z2", Notice.Kind.Approval))
    }

    @Test fun whatTheConfirmAsksAbout() {
        val paired = listOf("z2", "gym")
        val on = NotificationPrefs()
        assertFalse("nothing changed", on.silencesAnswers(on, paired))
        // Each way a prompt goes silent.
        assertTrue("the default", on.silencesAnswers(on.withDefault(NotifyGroup.Answers, on = false), paired))
        assertTrue("the master", on.silencesAnswers(on.copy(master = false), paired))
        assertTrue("a server off", on.silencesAnswers(on.withMode("gym", ServerNotify.Off), paired))
        assertTrue("a custom set", on.silencesAnswers(on.withCustom("gym", NotifyGroup.Answers, on = false), paired))
        // Other groups, and what doesn't reach a prompt.
        assertFalse(on.silencesAnswers(on.withDefault(NotifyGroup.Finished, on = false), paired))
        assertFalse(on.silencesAnswers(on.withCustom("gym", NotifyGroup.Finished, on = false), paired))
        assertFalse("choosing Custom changes nothing", on.silencesAnswers(on.withMode("gym", ServerNotify.Custom), paired))
        // Back to following defaults that have them off does silence it.
        val off = on.withDefault(NotifyGroup.Answers, on = false).withCustom("z2", NotifyGroup.Finished, on = false).withCustom("z2", NotifyGroup.Answers, on = true)
        assertTrue(off.silencesAnswers(off.withMode("z2", ServerNotify.Same), paired))
        // Already silent: not asked again.
        assertFalse(off.silencesAnswers(off.withMode("gym", ServerNotify.Off), paired))
        // Every server has its own, approvals on: the defaults reach nobody.
        val own = on.withMode("z2", ServerNotify.Custom).withMode("gym", ServerNotify.Custom)
        assertFalse(own.silencesAnswers(own.withDefault(NotifyGroup.Answers, on = false), paired))
        assertTrue("the master still does", own.silencesAnswers(own.copy(master = false), paired))
        // Under a master that's off, it would stay silent once the master is back.
        val quiet = on.copy(master = false)
        assertTrue(quiet.silencesAnswers(quiet.withMode("gym", ServerNotify.Off), paired))
        assertFalse("the master coming back silences nothing", quiet.silencesAnswers(on, paired))
    }

    @Test fun whatsChosenIsKept() {
        val kv = Mem()
        assertEquals(NotificationPrefs(), NotificationPrefs.load(kv))
        val p = NotificationPrefs(master = false)
            .withDefault(NotifyGroup.Finished, on = false)
            .withDefault(NotifyGroup.Limits, on = false)
            .withMode("0", ServerNotify.Off)
            .withCustom("abc123", NotifyGroup.Errors, on = false)
            // A Custom set with everything on is still one: it's not the defaults.
            .withCustom("def456", NotifyGroup.Finished, on = true)
            .withCustom("def456", NotifyGroup.Limits, on = true)
            // Same as all with a Custom kept for later.
            .withCustom("ghi789", NotifyGroup.Updates, on = false)
            .withMode("ghi789", ServerNotify.Same)
        p.save(kv)
        assertEquals(p, NotificationPrefs.load(kv))
        NotificationPrefs().save(kv)
        assertTrue("the defaults keep nothing", kv.m.isEmpty())
    }

    @Test fun whatAnotherBuildKeptIsReadAsFarAsItMeansAnything() {
        val kv = Mem()
        kv.put("notifyOff", "finished,gone,")
        kv.put("notifyServers", "z2:off\n\nbad\ngym:nonsense\nabc:custom:errors,gone\n:off")
        val p = NotificationPrefs.load(kv)
        assertEquals(setOf(NotifyGroup.Finished), p.off)
        assertEquals(setOf("z2", "abc"), p.servers.keys)
        assertEquals(setOf(NotifyGroup.Errors), p.customOf("abc"))
    }
}
