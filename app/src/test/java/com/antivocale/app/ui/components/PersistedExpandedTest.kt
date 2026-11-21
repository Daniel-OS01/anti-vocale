package com.antivocale.app.ui.components

import com.antivocale.app.data.requireDisclosureId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.lang.reflect.Modifier
import org.junit.Test

class PersistedExpandedTest {
    @Test
    fun `force open leaves the saved state alone and a tap can close it`() {
        assertEquals(DisclosureChange(open = true, persist = false), disclosureChange(false, userToggle = false))
        assertEquals(DisclosureChange(open = true, persist = false), disclosureChange(true, userToggle = false))
        assertEquals(DisclosureChange(open = false, persist = true), disclosureChange(true, userToggle = true))
        assertEquals(DisclosureChange(open = true, persist = true), disclosureChange(false, userToggle = true))
    }

    @Test
    fun `class persists only user toggles`() {
        val writes = mutableListOf<Boolean>()
        val state = PersistedExpanded(saved = { false }, write = writes::add)
        state.forceOpen()
        assertTrue(state.expanded)
        assertTrue(writes.isEmpty())
        state.toggle()
        assertEquals(false, state.expanded)
        assertEquals(listOf(false), writes)

        val otherWrites = mutableListOf<Boolean>()
        val other = PersistedExpanded(saved = { false }, write = otherWrites::add)
        other.toggle()
        assertEquals(true, other.expanded)
        assertEquals(listOf(true), otherWrites)
    }

    @Test
    fun `all disclosure ids are valid and unique`() {
        val ids = DisclosureIds::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name != "INSTANCE" }
            .map { it.get(null) as String }

        assertTrue(ids.isNotEmpty())
        ids.forEach {
            assertTrue(it.startsWith("settings.") || it.startsWith("models."))
            requireDisclosureId(it)
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ids cannot contain the storage separator`() {
        requireDisclosureId("models.header=extra")
    }
}
