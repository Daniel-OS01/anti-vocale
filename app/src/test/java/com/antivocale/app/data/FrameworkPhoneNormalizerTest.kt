package com.antivocale.app.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The framework normalizer delegates to PhoneNumberUtils.formatNumberToE164
 * (the platform's bundled libphonenumber). Fake numbers only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameworkPhoneNormalizerTest {

    @Test
    fun `national number with country gets E164`() {
        assertEquals("+972501234567", FrameworkPhoneNormalizer.toE164("0501234567", "IL"))
    }

    @Test
    fun `formatted international number with lowercase country gets E164`() {
        assertEquals("+393331234567", FrameworkPhoneNormalizer.toE164("+39 333 123 4567", "it"))
    }

    @Test
    fun `missing or blank country gives null`() {
        assertNull(FrameworkPhoneNormalizer.toE164("0501234567", null))
        assertNull(FrameworkPhoneNormalizer.toE164("0501234567", " "))
    }
}
