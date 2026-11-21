package com.antivocale.app.ui.viewmodel

import com.antivocale.app.data.FileSnapshot
import com.antivocale.app.data.TranscriptionProfile
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

    private val calls = TranscriptionProfile(backendId = "gigaam")
    private val own = TranscriptionProfile(backendId = "parakeet")
    private val rules = mapOf("Calls" to calls, "Calls/2024" to own)

    @Test
    fun `a set edited profile is the effective subfolder profile`() {
        val edited = TranscriptionProfile(language = "ru")
        assertEquals(edited, FolderRulePaths.effectiveSubfolderProfile(rules, "Calls/2024", edited))
    }

    @Test
    fun `a path without its own rule previews the ancestor rule`() {
        assertEquals(calls, FolderRulePaths.effectiveSubfolderProfile(mapOf("Calls" to calls), "Calls/2024", null))
        assertEquals(
            calls,
            FolderRulePaths.effectiveSubfolderProfile(mapOf("Calls" to calls), "Calls/2024", TranscriptionProfile()),
        )
    }

    @Test
    fun `clearing a path's own rule previews the ancestor rule, not the cleared one`() {
        assertEquals(calls, FolderRulePaths.effectiveSubfolderProfile(rules, "Calls/2024", null))
        assertEquals(calls, FolderRulePaths.effectiveSubfolderProfile(rules, "/Calls//2024/", TranscriptionProfile()))
    }

    @Test
    fun `no ancestor rule previews nothing`() {
        assertEquals(null, FolderRulePaths.effectiveSubfolderProfile(mapOf("Work" to calls), "Calls/2024", null))
        assertEquals(null, FolderRulePaths.effectiveSubfolderProfile(mapOf("Calls" to calls), "Calls", null))
    }
}
