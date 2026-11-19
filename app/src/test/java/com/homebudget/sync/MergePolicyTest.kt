package com.homebudget.sync

import com.homebudget.core.sync.IncomingVersion
import com.homebudget.core.sync.LocalVersion
import com.homebudget.core.sync.MergePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class MergePolicyTest {
    private fun d(local: LocalVersion?, inc: IncomingVersion, anc: Boolean = false) = MergePolicy.decide(local, inc, anc).let { it.apply to it.conflict }

    @Test fun `new record applies`() = assertEquals(true to false, d(null, IncomingVersion(1, "a", null, false)))

    @Test fun `causal successor applies without conflict`() =
        assertEquals(true to false, d(LocalVersion(1, "a", false), IncomingVersion(2, "b", "a", false)))

    @Test fun `duplicate operation ignored`() =
        assertEquals(false to false, d(LocalVersion(2, "b", false), IncomingVersion(2, "b", "a", false)))

    @Test fun `late ancestor ignored without conflict`() =
        assertEquals(false to false, d(LocalVersion(3, "c", false), IncomingVersion(2, "b", "a", false), anc = true))

    @Test fun `concurrent edits pick same winner on both devices`() {
        val a = IncomingVersion(2, "aaa", "base", false)
        val b = IncomingVersion(2, "bbb", "base", false)
        // Device holding A receives B -> B wins (greater op id); device holding B receives A -> keeps B.
        assertEquals(true to true, d(LocalVersion(2, "aaa", false), b))
        assertEquals(false to true, d(LocalVersion(2, "bbb", false), a))
    }

    @Test fun `higher revision wins concurrent edit`() =
        assertEquals(false to true, d(LocalVersion(3, "aaa", false), IncomingVersion(2, "zzz", "base", false)))

    @Test fun `tombstone is never resurrected`() =
        assertEquals(false to true, d(LocalVersion(2, "del", true), IncomingVersion(2, "edit", "base", false)))

    @Test fun `delete wins over concurrent edit and keeps conflict`() =
        assertEquals(true to true, d(LocalVersion(2, "edit", false), IncomingVersion(2, "del", "base", true)))

    @Test fun `delete of current version applies cleanly`() =
        assertEquals(true to false, d(LocalVersion(2, "x", false), IncomingVersion(3, "del", "x", true)))

    @Test fun `placeholder revision zero is replaced`() =
        assertEquals(true to false, d(LocalVersion(0, "manifest", false), IncomingVersion(1, "h", null, false)))
}
