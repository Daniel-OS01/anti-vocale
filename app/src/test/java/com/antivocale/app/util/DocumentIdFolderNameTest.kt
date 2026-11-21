package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentIdFolderNameTest {
    @Test
    fun `local path IDs return the immediate parent`() {
        assertEquals("local-tts", parentFolderNameFromDocumentId("primary:Download/local-tts/a.m4a"))
        assertEquals("Download", parentFolderNameFromDocumentId("raw:/storage/emulated/0/Download/x.ogg"))
        assertEquals("Voice Notes", parentFolderNameFromDocumentId("1A2B-3C4D:Voice Notes/x.ogg"))
    }

    @Test
    fun `root files and opaque or missing IDs carry no parent`() {
        listOf(null, "", " ", "primary:a.m4a", "raw:/x.ogg", "primary:Download/",
            "msf:1234", "audio:55", "document:12", "1234").forEach { id ->
            assertNull("Document ID: $id", parentFolderNameFromDocumentId(id))
        }
    }
}
