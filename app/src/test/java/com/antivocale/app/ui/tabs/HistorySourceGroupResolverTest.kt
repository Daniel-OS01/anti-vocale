package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R3 item 3: resolveHistoryGroup is the one origin-to-section mapping the
 * History grouping and the highlight auto-expand share. The device bug it
 * closes: every non-app row (folder sync, imports, shares) fell into one
 * "Other" section.
 */
class HistorySourceGroupResolverTest {

    private val callsUri = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val notesUri = "content://com.android.externalstorage.documents/tree/primary%3ANotes"
    private val calls = WatchedFolder(treeUri = callsUri, displayName = "Calls")
    private val notes = WatchedFolder(treeUri = notesUri, displayName = "Notes")

    private fun row(
        taskId: String = "t",
        pkg: String? = null,
        kind: String? = null,
        folderKey: String? = null,
        folderName: String? = null,
        subPath: String? = null,
        sender: String? = null,
    ) = LogEntry(
        taskId = taskId,
        type = LogEntry.Type.AUDIO,
        status = LogEntry.Status.SUCCESS,
        sourcePackageName = pkg,
        senderName = sender,
        sourceKind = kind,
        sourceFolderKey = folderKey,
        sourceFolderName = folderName,
        sourceSubPath = subPath,
    )

    private fun legacyTaskId(treeUri: String) = "fwatch_${treeUri.hashCode().toUInt()}_1700000000000_42"

    @Test
    fun `an app row keeps the app section and sender subgroups`() {
        val p = resolveHistoryGroup(row(pkg = "com.whatsapp", kind = SourceKind.SHARE, sender = "Anna"), emptyList())
        assertEquals("com.whatsapp", p.groupKey)
        assertEquals(HistoryGroupTitle.App("com.whatsapp"), p.title)
        assertEquals(HistorySubGroupRef("Anna", "Anna"), p.subGroup)
    }

    @Test
    fun `a folder-watch row is keyed by its folder and titled by its name`() {
        val p = resolveHistoryGroup(
            row(kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls"),
            listOf(calls),
        )
        assertEquals("folder:$callsUri", p.groupKey)
        assertEquals(HistoryGroupTitle.Folder("Calls"), p.title)
        assertNull(p.subGroup)
    }

    @Test
    fun `a folder-watch row nests under its subfolder before its caller label`() {
        val p = resolveHistoryGroup(
            row(kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls", subPath = "2026/11", sender = "Bob"),
            listOf(calls),
        )
        assertEquals("2026/11", p.subGroup?.label)
    }

    @Test
    fun `a folder-watch row without a subfolder falls back to the caller label`() {
        val p = resolveHistoryGroup(
            row(kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls", sender = "Bob"),
            listOf(calls),
        )
        assertEquals(HistorySubGroupRef("Bob", "Bob"), p.subGroup)
    }

    @Test
    fun `a subfolder and a caller with the same name do not share a subgroup key`() {
        val folders = listOf(calls)
        val bySub = resolveHistoryGroup(
            row(kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls", subPath = "Bob"), folders)
        val bySender = resolveHistoryGroup(
            row(kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls", sender = "Bob"), folders)
        assertEquals(bySub.groupKey, bySender.groupKey)
        assertNotEquals(bySub.subGroup?.key, bySender.subGroup?.key)
    }

    @Test
    fun `a browse row goes to Imported under its parent folder`() {
        val p = resolveHistoryGroup(row(kind = SourceKind.BROWSE, folderName = "Download"), emptyList())
        assertEquals("__imported__", p.groupKey)
        assertEquals(HistoryGroupTitle.Imported, p.title)
        assertEquals("Download", p.subGroup?.label)
    }

    @Test
    fun `a browse row without a parent folder sits directly under Imported`() {
        val p = resolveHistoryGroup(row(kind = SourceKind.BROWSE, sender = "ignored"), emptyList())
        assertEquals("__imported__", p.groupKey)
        assertNull(p.subGroup)
    }

    @Test
    fun `a share with no package goes to Shared with sender subgroups`() {
        val p = resolveHistoryGroup(row(kind = SourceKind.SHARE, sender = "Anna"), emptyList())
        assertEquals("__shared__", p.groupKey)
        assertEquals(HistoryGroupTitle.Shared, p.title)
        assertEquals(HistorySubGroupRef("Anna", "Anna"), p.subGroup)
    }

    @Test
    fun `automation and opentranscribe rows with no package go to Automation`() {
        for (kind in listOf(SourceKind.AUTOMATION, SourceKind.OPENTRANSCRIBE)) {
            val p = resolveHistoryGroup(row(kind = kind), emptyList())
            assertEquals(kind, "__automation__", p.groupKey)
            assertEquals(kind, HistoryGroupTitle.Automation, p.title)
        }
    }

    @Test
    fun `null kind, packageless voice note and unknown tokens stay Other`() {
        for (kind in listOf(null, SourceKind.VOICE_NOTE, "from_the_future")) {
            val p = resolveHistoryGroup(row(kind = kind), listOf(calls))
            assertEquals(kind.toString(), UNKNOWN_GROUP_KEY, p.groupKey)
            assertEquals(kind.toString(), HistoryGroupTitle.Other, p.title)
        }
    }

    @Test
    fun `a legacy fwatch row joins its uniquely matched folder`() {
        val p = resolveHistoryGroup(row(taskId = legacyTaskId(callsUri), sender = "Bob"), listOf(calls, notes))
        // Same key as the folder's new rows, so old and new share a section.
        assertEquals("folder:$callsUri", p.groupKey)
        assertEquals(HistoryGroupTitle.Folder("Calls"), p.title)
        assertEquals(HistorySubGroupRef("Bob", "Bob"), p.subGroup)
    }

    @Test
    fun `a legacy fwatch row with no matching folder goes to Folder sync`() {
        val p = resolveHistoryGroup(row(taskId = legacyTaskId(callsUri)), listOf(notes))
        assertEquals("__folder_sync__", p.groupKey)
        assertEquals(HistoryGroupTitle.FolderSyncLegacy, p.title)
    }

    @Test
    fun `a legacy fwatch row matching two folders goes to Folder sync`() {
        // Two distinct uris with one hash: the row cannot be attributed.
        val twinA = WatchedFolder(treeUri = "Aa", displayName = "A")
        val twinB = WatchedFolder(treeUri = "BB", displayName = "B")
        assertEquals(twinA.treeUri.hashCode(), twinB.treeUri.hashCode())
        val p = resolveHistoryGroup(row(taskId = legacyTaskId("Aa")), listOf(twinA, twinB))
        assertEquals("__folder_sync__", p.groupKey)
    }

    @Test
    fun `a non-fwatch task id with null kind is not mistaken for a legacy folder row`() {
        val p = resolveHistoryGroup(row(taskId = "fwatch_notanumber_1_2"), listOf(calls))
        assertEquals(UNKNOWN_GROUP_KEY, p.groupKey)
    }

    @Test
    fun `the highlight path and the grouping key one row identically`() {
        val r = row(taskId = "f1", kind = SourceKind.FOLDER_WATCH, folderKey = callsUri, folderName = "Calls", subPath = "a")
        val group = groupLogsByConversation(listOf(r), listOf(calls)).single()
        val p = resolveHistoryGroup(r, listOf(calls))
        assertEquals(p.groupKey, group.groupKey)
        assertEquals(p.subGroup!!.key, group.subGroups.single().key)
    }
}
