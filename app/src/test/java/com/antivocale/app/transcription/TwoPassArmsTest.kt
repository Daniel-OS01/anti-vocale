package com.antivocale.app.transcription

import android.content.Context
import com.antivocale.app.data.ExternalModelRecord
import com.antivocale.app.data.ExternalModelSource
import com.antivocale.app.data.ModelFamily
import com.antivocale.app.data.catalog.BundledCatalog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The shared fast-arm resolver both the runtime first pass and the Settings
 * gate consume: the preview pin first (when usable and not the accurate
 * side), else the built-in probe (stubbed here; ModelManagerCatalogTest
 * covers it), else a loadable streaming import.
 */
class TwoPassArmsTest {

    private val context: Context = mockk(relaxed = true)

    private fun external(
        id: String,
        family: ModelFamily,
        streaming: Boolean = false,
        sizeBytes: Long = 1L,
    ) = ExternalModelRecord(
        id = id,
        displayName = "Imported $id",
        dir = "/models/external/$id",
        family = family,
        modelType = if (family == ModelFamily.TRANSDUCER) "zipformer2" else "",
        languages = emptyList(),
        source = ExternalModelSource.LOCAL,
        sourceUrl = null,
        files = emptyMap(),
        sizeBytes = sizeBytes,
        importedAt = 0L,
        streaming = streaming,
    )

    private val stream = external("zip-stream", ModelFamily.TRANSDUCER, streaming = true)
    private val whisper = external("whisper-big", ModelFamily.WHISPER)
    private val ctc = external("ctc-small", ModelFamily.CTC)

    /** No backend has a saved model path. */
    private val noSavedPath: suspend (String) -> String? = { null }

    @Before
    fun setup() {
        mockkObject(SherpaModelManager.Companion)
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a usable external preview pin wins over every automatic arm`() = runTest {
        coEvery { SherpaModelManager.installedFastEntryId(any(), any()) } returns "whisper"
        assertEquals(ctc.backendId, TwoPassArms.fastBackendId(
            context, ctc.backendId, whisper.backendId, listOf(stream, whisper, ctc), noSavedPath))
        coVerify(exactly = 0) { SherpaModelManager.installedFastEntryId(any(), any()) }
    }

    @Test
    fun `a stale external preview pin falls through to the automatic choice`() = runTest {
        // The pinned record is no longer loadable (absent from the list).
        assertEquals(stream.backendId, TwoPassArms.fastBackendId(
            context, "external:gone", whisper.backendId, listOf(stream, whisper), noSavedPath))
    }

    @Test
    fun `a preview pin equal to the accurate side is ignored`() = runTest {
        assertEquals(stream.backendId, TwoPassArms.fastBackendId(
            context, whisper.backendId, whisper.backendId, listOf(stream, whisper), noSavedPath))
    }

    @Test
    fun `a usable built-in preview pin resolves on disk`() = runTest {
        mockkObject(BundledCatalog)
        every { BundledCatalog.byId(BuiltInBackendIds.WHISPER) } returns mockk(relaxed = true)
        val manager = mockk<SherpaModelManager> {
            every { resolveActiveModelPath(any(), any()) } returns "/models/whisper"
        }
        every { SherpaModelManager.of(BuiltInBackendIds.WHISPER) } returns manager
        assertEquals(BuiltInBackendIds.WHISPER, TwoPassArms.fastBackendId(
            context, BuiltInBackendIds.WHISPER, stream.backendId, listOf(stream), noSavedPath))
    }

    @Test
    fun `the llm is never a preview arm`() = runTest {
        assertNull(TwoPassArms.fastBackendId(
            context, LlmTranscriptionBackend.BACKEND_ID, whisper.backendId, listOf(whisper), noSavedPath))
    }

    @Test
    fun `the built-in choice precedes the streaming import`() = runTest {
        coEvery {
            SherpaModelManager.installedFastEntryId(any(), whisper.backendId)
        } returns BuiltInBackendIds.PARAKEET
        assertEquals(BuiltInBackendIds.PARAKEET, TwoPassArms.fastBackendId(
            context, "", whisper.backendId, listOf(stream, whisper), noSavedPath))
    }

    @Test
    fun `automatic picks a loadable streaming transducer import when no built-in resolves`() = runTest {
        assertEquals(stream.backendId, TwoPassArms.fastBackendId(
            context, "", whisper.backendId, listOf(whisper, stream), noSavedPath))
    }

    @Test
    fun `automatic never returns the accurate side nor a non-streaming import`() = runTest {
        // The streaming import IS the accurate side; the CTC import is not
        // streaming, so the automatic choice finds nothing (the picker case).
        assertNull(TwoPassArms.fastBackendId(
            context, "", stream.backendId, listOf(stream, ctc), noSavedPath))
    }

    @Test
    fun `automatic prefers the smallest of several streaming imports`() = runTest {
        val big = external("zip-big", ModelFamily.TRANSDUCER, streaming = true, sizeBytes = 900L)
        val small = external("zip-small", ModelFamily.TRANSDUCER, streaming = true, sizeBytes = 100L)
        assertEquals(small.backendId, TwoPassArms.fastBackendId(
            context, "", whisper.backendId, listOf(big, whisper, small), noSavedPath))
    }

    @Test
    fun `a built-in preview pin installed only at its saved path resolves like the loader`() = runTest {
        // The model lives outside its standard catalog directory: the
        // manager resolves it ONLY through the saved fallback path (the
        // runtime loader passes it), so the resolver must pass it too.
        val savedDir = "/sdcard/custom/whisper-model"
        mockkObject(BundledCatalog)
        every { BundledCatalog.byId(BuiltInBackendIds.WHISPER) } returns mockk(relaxed = true)
        val manager = mockk<SherpaModelManager> {
            every { resolveActiveModelPath(any(), any()) } returns null
            every { resolveActiveModelPath(any(), savedDir) } returns savedDir
        }
        every { SherpaModelManager.of(BuiltInBackendIds.WHISPER) } returns manager
        val savedPath: suspend (String) -> String? = { id ->
            savedDir.takeIf { id == BuiltInBackendIds.WHISPER }
        }
        assertEquals(BuiltInBackendIds.WHISPER, TwoPassArms.fastBackendId(
            context, BuiltInBackendIds.WHISPER, stream.backendId, listOf(stream), savedPath))
        // Without a saved path the same pin is unusable and automatic finds
        // nothing (the only import is the accurate side).
        assertNull(TwoPassArms.fastBackendId(
            context, BuiltInBackendIds.WHISPER, stream.backendId, listOf(stream), noSavedPath))
    }
}
