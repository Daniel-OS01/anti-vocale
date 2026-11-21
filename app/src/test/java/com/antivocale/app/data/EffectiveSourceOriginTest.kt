package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * R3 review: the ONE legacy folder-watch recovery the History grouping and
 * both retry paths share. The bug it closes: a retried pre-R3 folder-watch
 * row got a fresh task id, lost its fwatch evidence and moved to "Other".
 */
class EffectiveSourceOriginTest {

    private val callsUri = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val notesUri = "content://com.android.externalstorage.documents/tree/primary%3ANotes"
    private val calls = WatchedFolder(treeUri = callsUri, displayName = "Calls")
    private val notes = WatchedFolder(treeUri = notesUri, displayName = "Notes")
    private val none = SourceOrigin(kind = null, folderKey = null, folderName = null, subPath = null)

    private fun legacyTaskId(treeUri: String) = "fwatch_${treeUri.hashCode().toUInt()}_1700000000000_42"

    @Test
    fun `a legacy row whose tree hash matches one folder gets that folder`() {
        val origin = effectiveSourceOrigin(legacyTaskId(callsUri), none, listOf(notes, calls))
        assertEquals(SourceOrigin(SourceKind.FOLDER_WATCH, callsUri, "Calls", null), origin)
    }

    @Test
    fun `a legacy row matching no folder keeps only the kind`() {
        val origin = effectiveSourceOrigin(legacyTaskId(callsUri), none, listOf(notes))
        assertEquals(SourceOrigin(SourceKind.FOLDER_WATCH, null, null, null), origin)
    }

    @Test
    fun `a legacy row matching two folders keeps only the kind`() {
        // Two distinct uris with one hash: the row cannot be attributed.
        val twinA = WatchedFolder(treeUri = "Aa", displayName = "A")
        val twinB = WatchedFolder(treeUri = "BB", displayName = "B")
        assertEquals(twinA.treeUri.hashCode(), twinB.treeUri.hashCode())
        val origin = effectiveSourceOrigin(legacyTaskId("Aa"), none, listOf(twinA, twinB))
        assertEquals(SourceOrigin(SourceKind.FOLDER_WATCH, null, null, null), origin)
    }

    @Test
    fun `a row without an fwatch task id is returned unchanged`() {
        listOf("3f0c9a2e-uuid", "fwatch_notanumber_1_2", "fwatch_${callsUri.hashCode().toUInt()}").forEach { id ->
            assertSame(id, none, effectiveSourceOrigin(id, none, listOf(calls)))
        }
    }

    @Test
    fun `a row whose kind is already recorded is untouched`() {
        val share = SourceOrigin(SourceKind.SHARE, null, null, null)
        assertSame(share, effectiveSourceOrigin(legacyTaskId(callsUri), share, listOf(calls)))
        val recorded = SourceOrigin(SourceKind.FOLDER_WATCH, notesUri, "Notes", "2026/11")
        assertSame(recorded, effectiveSourceOrigin(legacyTaskId(callsUri), recorded, listOf(calls)))
    }

    @Test
    fun `a folder-watch row that lost its key is recovered keeping its subpath`() {
        val keyless = SourceOrigin(SourceKind.FOLDER_WATCH, null, null, "2026/11")
        val origin = effectiveSourceOrigin(legacyTaskId(callsUri), keyless, listOf(calls))
        assertEquals(SourceOrigin(SourceKind.FOLDER_WATCH, callsUri, "Calls", "2026/11"), origin)
    }
}
