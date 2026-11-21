package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FileSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRulePathsTest {

    private fun snap(name: String) = FileSnapshot(name = name, size = 1, enqueuedAt = null)

    @Test
    fun `subfolders and their parents are offered once, root files never`() {
        val discovered = FolderRulePaths.discovered(
            listOf(snap("root.m4a"), snap("Calls/Russian/a.m4a"), snap("Calls/Russian/b.m4a"), snap("Work/c.m4a")),
        )
        assertEquals(listOf("Calls", "Calls/Russian", "Work"), discovered)
    }

    @Test
    fun `a rule matches the scan through the normal form`() {
        val discovered = FolderRulePaths.discovered(listOf(snap("Calls/Russian/a.m4a")))
        assertTrue(FolderRulePaths.seenInLastScan("/Calls//Russian/", discovered))
        assertFalse(FolderRulePaths.seenInLastScan("Russian", discovered))
        assertFalse(FolderRulePaths.seenInLastScan("", discovered))
    }
}
