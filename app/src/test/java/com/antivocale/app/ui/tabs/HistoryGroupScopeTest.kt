package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.TranscriptionProfile
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * R6: HistoryGroupScope.parse reads a History header's rule scope back off
 * the keys resolveHistoryGroup writes. The round-trip cases build the keys
 * through the resolver, so a prefix change on one side fails here.
 */
class HistoryGroupScopeTest {

    // A real SAF tree uri: ':' (encoded and raw) and '/' must survive the parse.
    private val callsUri = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val rawColonUri = "content://com.example.provider/tree/primary:Music/Calls"
    private val calls = WatchedFolder(treeUri = callsUri, displayName = "Calls")

    private fun folderRow(treeUri: String = callsUri, subPath: String? = null, sender: String? = null) = LogEntry(
        taskId = "t",
        type = LogEntry.Type.AUDIO,
        status = LogEntry.Status.SUCCESS,
        senderName = sender,
        sourceKind = SourceKind.FOLDER_WATCH,
        sourceFolderKey = treeUri,
        sourceFolderName = "Calls",
        sourceSubPath = subPath,
    )

    @Test
    fun `a folder section header parses to its folder`() {
        val p = resolveHistoryGroup(folderRow(), listOf(calls))
        assertEquals(HistoryGroupScope.Folder(callsUri), HistoryGroupScope.parse(p.groupKey))
    }

    @Test
    fun `a subfolder sub-header parses to its folder and path`() {
        val p = resolveHistoryGroup(folderRow(subPath = "Russian/2024"), listOf(calls))
        assertEquals(
            HistoryGroupScope.Subfolder(callsUri, "Russian/2024"),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key),
        )
    }

    @Test
    fun `a subfolder path is normalized like a stored rule key`() {
        assertEquals(
            HistoryGroupScope.Subfolder(callsUri, "Russian/2024"),
            HistoryGroupScope.parse("folder:$callsUri", "dir:/Russian//2024/"),
        )
    }

    @Test
    fun `a sender sub-header under a folder parses to None`() {
        val p = resolveHistoryGroup(folderRow(sender = "Bob"), listOf(calls))
        assertEquals("who:Bob", p.subGroup?.key)
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse(p.groupKey, p.subGroup?.key))
    }

    @Test
    fun `app, imported and unknown sections parse to None`() {
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("com.whatsapp"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("com.whatsapp", "who:Anna"))
        // An imported row's parent-folder sub-group carries "dir:" too, but
        // its section is not a watched folder.
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("__imported__", "dir:Downloads"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse(UNKNOWN_GROUP_KEY))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("folder:"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("folder:$callsUri", "unknown:x"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("folder:$callsUri", "dir:/"))
    }

    @Test
    fun `a tree uri containing raw colons and slashes keeps every character`() {
        val p = resolveHistoryGroup(folderRow(treeUri = rawColonUri, subPath = "a:b/c"), emptyList())
        assertEquals(HistoryGroupScope.Folder(rawColonUri), HistoryGroupScope.parse(p.groupKey))
        assertEquals(
            HistoryGroupScope.Subfolder(rawColonUri, "a:b/c"),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key),
        )
    }

    @Test
    fun `only a still-watched folder resolves, and the stored rule is read per scope`() {
        val folderRule = TranscriptionProfile(language = "ru")
        val subRule = TranscriptionProfile(backendId = "sherpa:gigaam")
        val watched = calls.copy(profile = folderRule, subfolderRules = mapOf("Russian" to subRule))
        val folders = listOf(watched)

        val folder = HistoryGroupScope.Folder(callsUri)
        val sub = HistoryGroupScope.Subfolder(callsUri, "Russian")
        assertEquals(watched, folder.watchedFolderIn(folders))
        assertEquals(folderRule, folder.storedRuleIn(watched))
        assertEquals(subRule, sub.storedRuleIn(watched))
        assertNull(HistoryGroupScope.Subfolder(callsUri, "Other").storedRuleIn(watched))

        assertNull(HistoryGroupScope.Folder(rawColonUri).watchedFolderIn(folders))
        assertNull(HistoryGroupScope.None.watchedFolderIn(folders))
    }
}
