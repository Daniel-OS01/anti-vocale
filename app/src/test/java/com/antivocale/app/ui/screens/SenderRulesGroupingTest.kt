package com.antivocale.app.ui.screens

import com.antivocale.app.data.PerAppPreferencesManager
import com.antivocale.app.data.SenderKey
import com.antivocale.app.data.TranscriptionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R6: the sender-rules page's decode-and-group step (fake contacts only). */
class SenderRulesGroupingTest {

    private val ru = TranscriptionProfile(language = "ru")
    private val giga = TranscriptionProfile(backendId = "gigaam")

    private fun wa(pkg: String, value: String) = SenderKey.WhatsApp(pkg, value).storageId()

    @Test
    fun `rules group by source in page order and skip unparseable ids`() {
        val waId = wa(PerAppPreferencesManager.WHATSAPP, "Test Contact")
        val w4bId = wa(PerAppPreferencesManager.WHATSAPP_BUSINESS, "Test Contact")
        val callId = SenderKey.Call("+15550100").storageId()
        val sections = groupSenderRules(
            mapOf(
                callId to giga,
                "sender-opaque-1" to ru,
                w4bId to giga,
                "wa:com.example.other:Test Contact" to ru,
                waId to ru,
            ),
        )

        assertEquals(
            listOf(SenderRuleSource.WHATSAPP, SenderRuleSource.WHATSAPP_BUSINESS, SenderRuleSource.CALL),
            sections.map { it.source },
        )
        assertEquals(listOf(SenderRule(waId, SenderKey.WhatsApp(PerAppPreferencesManager.WHATSAPP, "Test Contact"), ru)),
            sections[0].rules)
        assertEquals(listOf(w4bId), sections[1].rules.map { it.storageId })
        assertEquals(listOf("+15550100"), sections[2].rules.map { it.key.value })
        assertEquals(giga, sections[2].rules.single().profile)
    }

    @Test
    fun `empty sections are omitted and rows sort by value ignoring case`() {
        val sections = groupSenderRules(
            mapOf(
                SenderKey.Call("test Contact B").storageId() to ru,
                SenderKey.Call("Test Contact A").storageId() to ru,
            ),
        )
        assertEquals(listOf(SenderRuleSource.CALL), sections.map { it.source })
        assertEquals(listOf("Test Contact A", "test Contact B"), sections.single().rules.map { it.key.value })
    }

    @Test
    fun `no rules or only unparseable ids give no sections`() {
        assertTrue(groupSenderRules(emptyMap()).isEmpty())
        assertTrue(groupSenderRules(mapOf("call:" to ru, "x" to giga)).isEmpty())
    }
}
