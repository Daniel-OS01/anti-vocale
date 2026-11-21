package com.antivocale.app.ui.tabs

import com.antivocale.app.data.PhoneNormalizer
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.SenderKeys
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

    private fun appRow(packageName: String, sender: String) = LogEntry(
        taskId = "t",
        type = LogEntry.Type.AUDIO,
        status = LogEntry.Status.SUCCESS,
        senderName = sender,
        sourcePackageName = packageName,
    )

    // Fake data only: "Test Contact", "+15550100".
    private val fakePhone = PhoneNormalizer { raw, iso -> if (iso == "us") "+1" + raw.filter(Char::isDigit) else null }

    @Test
    fun `a WhatsApp sender sub-header parses to its WhatsApp sender key`() {
        val p = resolveHistoryGroup(appRow("com.whatsapp", "Test Contact"), emptyList())
        assertEquals(
            HistoryGroupScope.Sender(SenderKey.WhatsApp("com.whatsapp", "Test Contact").storageId()),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key),
        )
    }

    @Test
    fun `a WhatsApp Business sender sub-header parses to the w4b key`() {
        val p = resolveHistoryGroup(appRow("com.whatsapp.w4b", "Test Contact"), emptyList())
        assertEquals(
            HistoryGroupScope.Sender("wa:com.whatsapp.w4b:Test Contact"),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key),
        )
    }

    @Test
    fun `a caller sub-header under a folder parses through the normalizer to a call key`() {
        val p = resolveHistoryGroup(folderRow(sender = "5550100"), listOf(calls))
        assertEquals("who:5550100", p.subGroup?.key)
        assertEquals(
            HistoryGroupScope.Sender(SenderKey.Call("+15550100").storageId()),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key, countryIso = "us", phone = fakePhone),
        )
        // The same key the folder scan derives for that caller.
        assertEquals(
            SenderKeys.forCallRecording("5550100", "us", fakePhone)?.storageId(),
            (HistoryGroupScope.parse(p.groupKey, p.subGroup?.key, "us", fakePhone) as HistoryGroupScope.Sender).storageId,
        )
        // A named caller keys by name, with no normalizer involved.
        assertEquals(
            HistoryGroupScope.Sender("call:Test Contact"),
            HistoryGroupScope.parse(p.groupKey, "who:Test Contact", countryIso = "us", phone = fakePhone),
        )
    }

    @Test
    fun `a caller sub-header under the legacy Folder sync section parses to a call key`() {
        // A folder-watch row whose folder cannot be recovered lands in the
        // legacy section; its caller still keys as a call recording.
        val unattributed = LogEntry(
            taskId = "t",
            type = LogEntry.Type.AUDIO,
            status = LogEntry.Status.SUCCESS,
            senderName = "5550100",
            sourceKind = SourceKind.FOLDER_WATCH,
        )
        val p = resolveHistoryGroup(unattributed, listOf(calls))
        assertEquals("__folder_sync__", p.groupKey)
        assertEquals("who:5550100", p.subGroup?.key)
        assertEquals(
            HistoryGroupScope.Sender(SenderKey.Call("+15550100").storageId()),
            HistoryGroupScope.parse(p.groupKey, p.subGroup?.key, countryIso = "us", phone = fakePhone),
        )
        assertEquals(
            HistoryGroupScope.Sender("call:Test Contact"),
            HistoryGroupScope.parse(p.groupKey, "who:Test Contact", countryIso = "us", phone = fakePhone),
        )
        // The section header itself edits nothing.
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse(p.groupKey))
    }

    @Test
    fun `a row's own sender key covers rows no caller header reaches`() {
        // A recording in a subfolder: its header is the subfolder, not the caller.
        assertEquals(
            SenderKey.Call("+15550100"),
            senderKeyForRow(folderRow(subPath = "Russian", sender = "5550100"), listOf(calls), "us", fakePhone),
        )
        // A pre-R3 folder-watch row recorded no kind; its task id still marks it.
        val legacy = LogEntry(
            taskId = "fwatch_1_2_3",
            type = LogEntry.Type.AUDIO,
            status = LogEntry.Status.SUCCESS,
            senderName = "Test Contact",
        )
        assertEquals(SenderKey.Call("Test Contact"), senderKeyForRow(legacy, emptyList(), "us", fakePhone))
        // A WhatsApp share keys as a share; another app's row has no key.
        assertEquals(
            SenderKey.WhatsApp("com.whatsapp", "Test Contact"),
            senderKeyForRow(appRow("com.whatsapp", "Test Contact"), emptyList(), "us", fakePhone),
        )
        assertNull(senderKeyForRow(appRow("org.example.messenger", "Test Contact"), emptyList(), "us", fakePhone))
    }

    @Test
    fun `a sender scope needs no watched folder and stores no folder rule`() {
        val sender = HistoryGroupScope.Sender("call:+15550100")
        assertNull(sender.treeUri)
        assertNull(sender.watchedFolderIn(listOf(calls)))
        assertNull(sender.storedRuleIn(calls))
    }

    @Test
    fun `app, imported and unknown sections parse to None`() {
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("com.whatsapp"))
        // Another app's sender sub-header carries no sender key.
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("org.telegram.messenger", "who:Test Contact"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("com.whatsapp", "dir:Test Contact"))
        assertSame(HistoryGroupScope.None, HistoryGroupScope.parse("__shared__", "who:Test Contact"))
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
