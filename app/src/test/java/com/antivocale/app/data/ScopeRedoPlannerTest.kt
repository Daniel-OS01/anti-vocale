package com.antivocale.app.data

import com.antivocale.app.data.local.LogEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which existing rows a saved folder or subfolder rule governs (the redo offer). */
class ScopeRedoPlannerTest {

    private fun row(id: String, subPath: String?) = LogEntity(
        id = id,
        timestamp = 1L,
        taskId = "task-$id",
        type = "AUDIO",
        status = "SUCCESS",
        sourceSubPath = subPath,
    )

    private val rows = listOf(
        row("root", null),
        row("misc", "Misc"),
        row("ru", "Russian"),
        row("ru-2024", "Russian/2024"),
        row("ru-work", "Russian/Work"),
        row("ru-work-deep", "Russian/Work/Q1"),
        row("russians", "Russians"),
    )
    private val rules = setOf("Russian", "Russian/Work")
    private val noSender: (LogEntity) -> Boolean = { false }

    @Test
    fun `folder scope keeps only rows no subfolder rule matches`() {
        assertEquals(
            listOf("root", "misc", "russians"),
            ScopeRedoPlanner.plan(rows, rules, scopeSubPath = null, senderRuled = noSender),
        )
    }

    @Test
    fun `subfolder scope keeps nested unruled rows and drops deeper-ruled rows`() {
        assertEquals(
            listOf("ru", "ru-2024"),
            ScopeRedoPlanner.plan(rows, rules, scopeSubPath = "Russian", senderRuled = noSender),
        )
        assertEquals(
            listOf("ru-work", "ru-work-deep"),
            ScopeRedoPlanner.plan(rows, rules, scopeSubPath = "Russian/Work", senderRuled = noSender),
        )
    }

    @Test
    fun `the scope path is matched in normal form`() {
        assertEquals(
            listOf("ru", "ru-2024"),
            ScopeRedoPlanner.plan(rows, rules, scopeSubPath = "/Russian//", senderRuled = noSender),
        )
    }

    @Test
    fun `sender-ruled rows are excluded in both scopes`() {
        val ruled: (LogEntity) -> Boolean = { it.id == "misc" || it.id == "ru-2024" }
        assertEquals(listOf("root", "russians"), ScopeRedoPlanner.plan(rows, rules, null, ruled))
        assertEquals(listOf("ru"), ScopeRedoPlanner.plan(rows, rules, "Russian", ruled))
    }

    @Test
    fun `a removed subfolder rule still offers the rows it governed`() {
        // After removing "Russian" only the deeper rule remains.
        assertEquals(
            listOf("ru", "ru-2024"),
            ScopeRedoPlanner.plan(rows, setOf("Russian/Work"), scopeSubPath = "Russian", senderRuled = noSender),
        )
    }

    @Test
    fun `a removed folder default offers the rows outside every subfolder rule`() {
        assertEquals(
            listOf("root", "misc", "russians"),
            ScopeRedoPlanner.plan(rows, rules, scopeSubPath = null, senderRuled = noSender),
        )
        // With no subfolder rules at all, every row is the folder's.
        assertEquals(rows.map { it.id }, ScopeRedoPlanner.plan(rows, emptySet(), null, noSender))
    }

    @Test
    fun `nothing governed gives an empty plan`() {
        assertEquals(emptyList<String>(), ScopeRedoPlanner.plan(emptyList(), rules, null, noSender))
        assertEquals(emptyList<String>(), ScopeRedoPlanner.plan(rows, rules, "Italian", noSender))
        assertEquals(emptyList<String>(), ScopeRedoPlanner.plan(rows, rules, null) { true })
    }
}
