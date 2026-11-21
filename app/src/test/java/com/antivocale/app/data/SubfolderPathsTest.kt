package com.antivocale.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubfolderPathsTest {

    @Test
    fun `normalize trims, collapses slashes and strips the ends`() {
        assertEquals("Russian/2024", SubfolderPaths.normalize("  //Russian///2024/ "))
        assertEquals("a", SubfolderPaths.normalize("a"))
    }

    @Test
    fun `blank and slash-only paths are the root`() {
        assertNull(SubfolderPaths.normalize(null))
        assertNull(SubfolderPaths.normalize(""))
        assertNull(SubfolderPaths.normalize("   "))
        assertNull(SubfolderPaths.normalize("///"))
    }

    @Test
    fun `normalize applies NFC so composed and decomposed forms are equal`() {
        val composed = "Café"
        val decomposed = "Café"
        assertEquals(composed, SubfolderPaths.normalize(decomposed))
        assertTrue(SubfolderPaths.matches(composed, "$decomposed/2024"))
        assertTrue(SubfolderPaths.matches(decomposed, composed))
    }

    @Test
    fun `matches on segment boundaries only`() {
        assertTrue(SubfolderPaths.matches("Russian", "Russian"))
        assertTrue(SubfolderPaths.matches("Russian", "Russian/2024"))
        assertTrue(SubfolderPaths.matches("/Russian/", "Russian//2024/x"))
        assertFalse(SubfolderPaths.matches("Russian", "Russians"))
        assertFalse(SubfolderPaths.matches("Russian", "Old/Russian"))
        assertFalse(SubfolderPaths.matches("Russian/2024", "Russian"))
    }

    @Test
    fun `a root subPath or a root rule matches nothing`() {
        assertFalse(SubfolderPaths.matches("Russian", null))
        assertFalse(SubfolderPaths.matches("Russian", ""))
        assertFalse(SubfolderPaths.matches("/", "Russian"))
    }

    @Test
    fun `longestMatch picks the deepest matching rule`() {
        val rules = mapOf("Russian" to "shallow", "Russian/2024" to "deep", "Russians" to "other")
        assertEquals("deep", SubfolderPaths.longestMatch(rules, "Russian/2024/jan"))
        assertEquals("shallow", SubfolderPaths.longestMatch(rules, "Russian/2023"))
        assertEquals("other", SubfolderPaths.longestMatch(rules, "Russians"))
        assertNull(SubfolderPaths.longestMatch(rules, "Italian"))
        assertNull(SubfolderPaths.longestMatch(rules, null))
    }
}
