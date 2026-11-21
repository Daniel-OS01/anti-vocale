package com.antivocale.app.ui.tabs

import com.antivocale.app.data.SourceKind
import com.antivocale.app.data.WatchedFolder
import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for indexOfTaskId — the flat LazyColumn index lookup
 * used by the notification-to-content highlight feature.
 */
class IndexOfTaskIdTest {

    private fun makeEntry(taskId: String) =
        LogEntry(
            taskId = taskId,
            type = LogEntry.Type.AUDIO,
            status = LogEntry.Status.SUCCESS
        )

    @Test
    fun `finds entry in single group`() {
        val groups = listOf(
            DateGroup("Today", listOf(
                makeEntry("a"),
                makeEntry("b"),
                makeEntry("c")
            ))
        )
        // advisory=0, "Today" header=1, a=2, b=3, c=4
        assertEquals(2, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(3, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(4, indexOfTaskIdInGroups(groups, "c"))
    }

    @Test
    fun `returns -1 when taskId not found`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a")))
        )
        assertEquals(-1, indexOfTaskIdInGroups(groups, "missing"))
    }

    @Test
    fun `returns -1 for empty groups`() {
        assertEquals(-1, indexOfTaskIdInGroups(emptyList(), "a"))
    }

    @Test
    fun `accounts for multiple group headers`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("a"), makeEntry("b"))),
            DateGroup("Yesterday", listOf(makeEntry("c"), makeEntry("d")))
        )
        // advisory=0, Today header=1, a=2, b=3, Yesterday header=4, c=5, d=6
        assertEquals(2, indexOfTaskIdInGroups(groups, "a"))
        assertEquals(3, indexOfTaskIdInGroups(groups, "b"))
        assertEquals(5, indexOfTaskIdInGroups(groups, "c"))
        assertEquals(6, indexOfTaskIdInGroups(groups, "d"))
    }

    @Test
    fun `finds entry in last group with many groups`() {
        val groups = listOf(
            DateGroup("Group1", listOf(makeEntry("a"))),
            DateGroup("Group2", listOf(makeEntry("b"))),
            DateGroup("Group3", listOf(makeEntry("c"))),
            DateGroup("Group4", listOf(makeEntry("target")))
        )
        // advisory=0; G1 header=1, a=2; G2 header=3, b=4; G3 header=5, c=6; G4 header=7, target=8
        assertEquals(8, indexOfTaskIdInGroups(groups, "target"))
    }

    @Test
    fun `finds entry in group with single entry`() {
        val groups = listOf(
            DateGroup("Today", listOf(makeEntry("only-one")))
        )
        assertEquals(2, indexOfTaskIdInGroups(groups, "only-one"))
    }

    /**
     * TASK-737: sender sub-sections inside a conversation section. The flat
     * index must count each sender header and skip the rows of collapsed
     * senders, while unlabeled rows stay directly under the section header.
     */
    private fun senderEntry(taskId: String, sender: String?) =
        makeEntry(taskId).copy(senderName = sender)

    @Test
    fun `counts sender headers when all senders expanded`() {
        val group = ConversationGroup(
            groupKey = "com.whatsapp",
            title = HistoryGroupTitle.App("com.whatsapp"),
            logs = listOf(
                stamped("a1", at = 300).copy(senderName = "Anna"),
                stamped("u1", at = 100).copy(senderName = null),
                stamped("b1", at = 200).copy(senderName = "Bruno")
            ),
            subGroups = listOf(
                SubGroup("Anna", "Anna", listOf(stamped("a1", at = 300).copy(senderName = "Anna"))),
                SubGroup("Bruno", "Bruno", listOf(stamped("b1", at = 200).copy(senderName = "Bruno")))
            ),
            unlabeledLogs = listOf(stamped("u1", at = 100).copy(senderName = null))
        )
        // [0] fixed, [1] section header, [2] Anna header, [3] a1,
        // [4] Bruno header, [5] b1, [6] u1
        val groups = listOf(group)
        assertEquals(3, indexOfTaskIdInGroups(groups, "a1"))
        assertEquals(5, indexOfTaskIdInGroups(groups, "b1"))
        assertEquals(6, indexOfTaskIdInGroups(groups, "u1"))
    }

    @Test
    fun `skips rows of collapsed senders`() {
        val group = ConversationGroup(
            groupKey = "com.whatsapp",
            title = HistoryGroupTitle.App("com.whatsapp"),
            logs = listOf(
                senderEntry("a1", "Anna"),
                senderEntry("a2", "Anna"),
                senderEntry("b1", "Bruno")
            ),
            subGroups = listOf(
                SubGroup("Anna", "Anna", listOf(senderEntry("a1", "Anna"), senderEntry("a2", "Anna"))),
                SubGroup("Bruno", "Bruno", listOf(senderEntry("b1", "Bruno")))
            ),
            unlabeledLogs = emptyList()
        )
        val groups = listOf(group)
        // Anna collapsed: [1] section header, [2] Anna header (rows hidden),
        // [3] Bruno header, [4] b1
        assertEquals(4, indexOfTaskIdInGroups(groups, "b1") { it != "com.whatsapp|Anna" })
        // a target inside a still-collapsed sender is simply not rendered:
        // the honest answer is the index the row WOULD have once expanded
        // ([2] Anna header, [3] a1, [4] a2); the auto-expand path expands
        // the sender before asking.
        assertEquals(3, indexOfTaskIdInGroups(groups, "a1") { true })
        assertEquals(-1, indexOfTaskIdInGroups(groups, "missing") { true })
    }

    @Test
    fun `group without sender groups keeps the legacy math`() {
        val group = ConversationGroup(
            groupKey = "com.whatsapp",
            title = HistoryGroupTitle.App("com.whatsapp"),
            logs = listOf(makeEntry("a"), makeEntry("b")),
            subGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("a"), makeEntry("b"))
        )
        // [1] section header, [2] a, [3] b regardless of the predicate
        assertEquals(2, indexOfTaskIdInGroups(listOf(group), "a") { false })
        assertEquals(3, indexOfTaskIdInGroups(listOf(group), "b"))
    }

    /**
     * 737 review F1: whichever block holds the NEWEST row is counted (and
     * rendered) first, so a fresh unlabeled share stays above older sender
     * groups. Equal timestamps keep senders first (the messaging default).
     */
    private fun stamped(taskId: String, at: Long) =
        makeEntry(taskId).copy(timestamp = at)

    @Test
    fun `a collapsed section above the target hides its rows from the count`() {
        val whatsapp = ConversationGroup(
            groupKey = "com.whatsapp",
            title = HistoryGroupTitle.App("com.whatsapp"),
            logs = listOf(makeEntry("w1"), makeEntry("w2")),
            subGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("w1"), makeEntry("w2"))
        )
        val telegram = ConversationGroup(
            groupKey = "org.telegram",
            title = HistoryGroupTitle.App("org.telegram"),
            logs = listOf(makeEntry("t1")),
            subGroups = emptyList(),
            unlabeledLogs = listOf(makeEntry("t1"))
        )
        val groups = listOf(whatsapp, telegram)
        // WhatsApp collapsed: [1] WhatsApp header, [2] Telegram header, [3] t1.
        // Named arguments on purpose: with two predicates a positional
        // trailing lambda silently binds to the SENDER one.
        assertEquals(3, indexOfTaskIdInGroups(groups, "t1", isSectionExpanded = { it != "com.whatsapp" }))
        // both expanded: [1] WA header, [2..3] rows, [4] TG header, [5] t1
        assertEquals(5, indexOfTaskIdInGroups(groups, "t1"))
    }

    @Test
    fun `a newer unlabeled row is counted before the sender blocks`() {
        val group = ConversationGroup(
            groupKey = "com.whatsapp",
            title = HistoryGroupTitle.App("com.whatsapp"),
            logs = listOf(
                stamped("a1", at = 100),
                stamped("u1", at = 200)
            ),
            subGroups = listOf(SubGroup("Anna", "Anna", listOf(stamped("a1", at = 100)))),
            unlabeledLogs = listOf(stamped("u1", at = 200))
        )
        // [1] section, [2] u1 (newest block first), [3] Anna header, [4] a1
        assertEquals(2, indexOfTaskIdInGroups(listOf(group), "u1"))
        assertEquals(4, indexOfTaskIdInGroups(listOf(group), "a1"))
    }

    /**
     * R3 item 3: the origin sections go through resolveHistoryGroup, so the
     * index math here and the render rows key the SAME sections; these
     * build the groups with the production grouping, not by hand.
     */
    private val callsUri = "content://com.android.externalstorage.documents/tree/primary%3ACalls"
    private val calls = WatchedFolder(treeUri = callsUri, displayName = "Calls")

    private fun originRows() = listOf(
        stamped("w1", at = 300).copy(sourcePackageName = "com.whatsapp"),
        stamped("f1", at = 200).copy(
            sourceKind = SourceKind.FOLDER_WATCH,
            sourceFolderKey = callsUri,
            sourceFolderName = "Calls",
        ),
        stamped("b1", at = 100).copy(
            sourceKind = SourceKind.BROWSE,
            sourceFolderName = "Download",
        ),
    )

    @Test
    fun `folder-watch and browse rows get their own sections when expanded`() {
        val groups = groupLogsByConversation(originRows(), listOf(calls))
        // [1] WhatsApp, [2] w1, [3] Calls folder, [4] f1,
        // [5] Imported, [6] Download sub-header, [7] b1
        assertEquals(4, indexOfTaskIdInGroups(groups, "f1"))
        assertEquals(7, indexOfTaskIdInGroups(groups, "b1"))
    }

    @Test
    fun `a collapsed folder section hides only its own rows`() {
        val groups = groupLogsByConversation(originRows(), listOf(calls))
        val folderKey = resolveHistoryGroup(originRows()[1], listOf(calls)).groupKey
        // Calls collapsed: [1] WA, [2] w1, [3] Calls header, [4] Imported,
        // [5] Download sub-header, [6] b1
        assertEquals(6, indexOfTaskIdInGroups(groups, "b1", isSectionExpanded = { it != folderKey }))
        // Imported collapsed: its rows vanish, the folder row stays at [4]
        val importedKey = resolveHistoryGroup(originRows()[2], listOf(calls)).groupKey
        assertEquals(4, indexOfTaskIdInGroups(groups, "f1", isSectionExpanded = { it != importedKey }))
    }

    @Test
    fun `a legacy fwatch row joins its uniquely matched folder section`() {
        val legacy = stamped("fwatch_${callsUri.hashCode().toUInt()}_1700000000000_42", at = 150)
        val groups = groupLogsByConversation(originRows() + legacy, listOf(calls))
        // [3] Calls header, [4] f1, [5] legacy row (same section), [6] Imported
        assertEquals(5, indexOfTaskIdInGroups(groups, legacy.taskId))
        assertEquals(8, indexOfTaskIdInGroups(groups, "b1"))
    }

    @Test
    fun `a legacy fwatch row with no folder match lands in Folder sync`() {
        val legacy = stamped("fwatch_12345_1700000000000_42", at = 50)
        val groups = groupLogsByConversation(originRows() + legacy, listOf(calls))
        // [5] Imported, [6] Download, [7] b1, [8] Folder sync header, [9] row
        assertEquals(9, indexOfTaskIdInGroups(groups, legacy.taskId))
        val syncKey = resolveHistoryGroup(legacy, listOf(calls)).groupKey
        // Folder sync collapsed: the row is not rendered, so the walk finds nothing
        assertEquals(-1, indexOfTaskIdInGroups(groups, legacy.taskId, isSectionExpanded = { it != syncKey }))
    }
}
