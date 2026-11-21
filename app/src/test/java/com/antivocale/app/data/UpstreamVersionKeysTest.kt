package com.antivocale.app.data

import com.antivocale.app.BuildConfig
import com.antivocale.app.testing.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fork releases inject their own versionName (CI_VERSION_NAME, e.g. "1.2.0"), which
 * shares nothing with upstream's numbering. Lookups keyed on upstream releases must
 * keep using the upstream version; ci.yml runs this suite with a fork-style
 * CI_VERSION_NAME so a regression fails here, not on the phone.
 */
class UpstreamVersionKeysTest {

    @Test
    fun `the bundled catalog index the build points at exists`() {
        val asset = RepoFiles.resolve("src/main/assets/${BuildConfig.CATALOG_INDEX_ASSET}")
        assertTrue(
            "BuildConfig.CATALOG_INDEX_ASSET names a missing asset: ${BuildConfig.CATALOG_INDEX_ASSET} " +
                "(versionName ${BuildConfig.VERSION_NAME})",
            asset.isFile,
        )
        val index = "external-catalog/index-${BuildConfig.UPSTREAM_VERSION_NAME.substringBefore("-")}.json"
        assertEquals(index, BuildConfig.CATALOG_INDEX_ASSET)
        assertTrue(
            "CATALOG_INDEX_URL is not keyed on the upstream version: ${BuildConfig.CATALOG_INDEX_URL}",
            BuildConfig.CATALOG_INDEX_URL.endsWith("/app/src/main/assets/$index"),
        )
    }
}
