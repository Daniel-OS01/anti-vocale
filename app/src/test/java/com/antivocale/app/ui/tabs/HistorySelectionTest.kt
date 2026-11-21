package com.antivocale.app.ui.tabs

import com.antivocale.app.ui.viewmodel.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySelectionTest {

    @Test
    fun `toggle adds an absent id and removes a present one`() {
        assertEquals(setOf("a"), HistorySelection.toggle(emptySet(), "a"))
        assertEquals(setOf("b"), HistorySelection.toggle(setOf("a", "b"), "a"))
        assertEquals(emptySet<String>(), HistorySelection.toggle(setOf("a"), "a"))
    }

    @Test
    fun `setAll on adds the ids and keeps the rest`() {
        assertEquals(setOf("x", "a", "b"), HistorySelection.setAll(setOf("x"), listOf("a", "b"), on = true))
        assertEquals(setOf("a"), HistorySelection.setAll(setOf("a"), listOf("a"), on = true))
    }

    @Test
    fun `setAll off removes only the given ids`() {
        assertEquals(setOf("x"), HistorySelection.setAll(setOf("x", "a", "b"), listOf("a", "b", "z"), on = false))
    }

    @Test
    fun `setAll with no ids is the identity`() {
        assertEquals(setOf("x"), HistorySelection.setAll(setOf("x"), emptyList(), on = true))
        assertEquals(setOf("x"), HistorySelection.setAll(setOf("x"), emptyList(), on = false))
    }

    @Test
    fun `prune drops ids that no longer exist`() {
        assertEquals(setOf("a"), HistorySelection.prune(setOf("a", "gone"), setOf("a", "b")))
        assertEquals(emptySet<String>(), HistorySelection.prune(setOf("gone"), emptySet()))
    }

    @Test
    fun `prune never adds existing ids`() {
        assertEquals(setOf("a"), HistorySelection.prune(setOf("a"), setOf("a", "b", "c")))
        assertEquals(emptySet<String>(), HistorySelection.prune(emptySet(), setOf("a")))
    }

    @Test
    fun `triState is OFF ON or PARTIAL over the group`() {
        val group = listOf("a", "b")
        assertEquals(GroupSelection.OFF, HistorySelection.triState(group, setOf("x")))
        assertEquals(GroupSelection.PARTIAL, HistorySelection.triState(group, setOf("a", "x")))
        assertEquals(GroupSelection.ON, HistorySelection.triState(group, setOf("a", "b", "x")))
    }

    @Test
    fun `an empty group is OFF even with a selection`() {
        assertEquals(GroupSelection.OFF, HistorySelection.triState(emptyList(), setOf("a")))
        assertEquals(GroupSelection.OFF, HistorySelection.triState(emptyList(), emptySet()))
    }

    @Test
    fun `headerToggle clears a fully selected group and keeps outside ids`() {
        assertEquals(setOf("x"), HistorySelection.headerToggle(listOf("a", "b"), setOf("a", "b", "x")))
    }

    @Test
    fun `headerToggle selects the whole group from OFF or PARTIAL`() {
        assertEquals(setOf("a", "b"), HistorySelection.headerToggle(listOf("a", "b"), emptySet()))
        assertEquals(setOf("a", "b", "x"), HistorySelection.headerToggle(listOf("a", "b"), setOf("a", "x")))
    }

    @Test
    fun `headerToggle on an empty group changes nothing`() {
        assertEquals(setOf("x"), HistorySelection.headerToggle(emptyList(), setOf("x")))
    }

    @Test
    fun `hiddenCount counts selected ids outside the visible set`() {
        assertEquals(0, HistorySelection.hiddenCount(emptySet(), setOf("a")))
        assertEquals(0, HistorySelection.hiddenCount(setOf("a"), setOf("a", "b")))
        assertEquals(2, HistorySelection.hiddenCount(setOf("a", "h1", "h2"), setOf("a")))
        assertEquals(1, HistorySelection.hiddenCount(setOf("h"), emptySet()))
    }

    private fun subGroup(key: String, vararg ids: String) = SubGroup(
        key = key,
        label = key,
        logs = ids.map { LogEntry(id = it, taskId = "task-$it", type = LogEntry.Type.AUDIO, status = LogEntry.Status.SUCCESS) },
    )

    @Test
    fun `select with subfolders takes the subfolder and its descendants only`() {
        val section = listOf(
            subGroup("dir:Russian", "r1", "r2"),
            subGroup("dir:Russian/2024", "r3"),
            subGroup("dir:Russians", "x1"),
            subGroup("who:x", "w1"),
        )
        assertEquals(setOf("r1", "r2", "r3"), HistorySelection.subfolderTreeIds("dir:Russian", section))
        // From a deeper level: the parent is not "below" it.
        assertEquals(setOf("r3"), HistorySelection.subfolderTreeIds("dir:Russian/2024", section))
    }

    @Test
    fun `select with subfolders from a sender sub-group selects nothing`() {
        val section = listOf(subGroup("who:x", "w1"), subGroup("dir:x", "d1"))
        assertEquals(emptySet<String>(), HistorySelection.subfolderTreeIds("who:x", section))
    }
}
