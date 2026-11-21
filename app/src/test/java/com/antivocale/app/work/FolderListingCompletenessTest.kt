package com.antivocale.app.work

import android.os.Bundle
import android.provider.DocumentsContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A SAF child cursor that is still loading or carries an error is a FAILED
 * directory listing, never a complete one: read as complete, an empty partial
 * subfolder would let the recursive upgrade finish without baselining it, and
 * unseen files would read as vanished. Robolectric for a real [Bundle].
 */
@RunWith(RobolectricTestRunner::class)
class FolderListingCompletenessTest {

    @Test
    fun `a cursor without extras or with empty extras is complete`() {
        assertTrue(FolderListing.isCompleteListing(null))
        assertTrue(FolderListing.isCompleteListing(Bundle()))
    }

    @Test
    fun `a loading cursor is not complete`() {
        val extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
        assertFalse(FolderListing.isCompleteListing(extras))
    }

    @Test
    fun `a cursor whose loading flag is false is complete`() {
        val extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, false) }
        assertTrue(FolderListing.isCompleteListing(extras))
    }

    @Test
    fun `an errored cursor is not complete`() {
        val extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "network unavailable") }
        assertFalse(FolderListing.isCompleteListing(extras))
    }
}
