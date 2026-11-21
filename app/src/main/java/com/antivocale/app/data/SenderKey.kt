package com.antivocale.app.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.text.Normalizer
import java.util.Locale

// File-level rather than companion-private: the interface's default
// storageId() reads them, and a private companion member read from an
// interface body needs a synthetic accessor the JVM default-method mode
// may not generate.
private const val WA_PREFIX = "wa:"
private const val CALL_PREFIX = "call:"

/**
 * The stable, normalized identity a per-sender rule is keyed on.
 *
 * Contract:
 * - A key is personal data (a contact name or a phone number). NEVER log
 *   [value] or [storageId]; a log line may say only which kind matched
 *   (`sender_rule=matched|none source=wa|call`).
 * - WhatsApp keys are the notification display name, so a contact rename
 *   means the rule stops matching: the lookup falls through to the default,
 *   it never guesses a near match.
 * - [Call] is keyed by source kind, not by watched folder, so the same caller
 *   matches in any watched folder.
 */
sealed interface SenderKey {
    val value: String

    /** A WhatsApp sender; [packageName] is one of the two WhatsApp clients. */
    data class WhatsApp(val packageName: String, override val value: String) : SenderKey {
        init {
            require(packageName in WHATSAPP_PACKAGES) { "not a WhatsApp package" }
        }
    }

    /** A call-recording caller: an E.164 number, a digits-only number, or a name. */
    data class Call(override val value: String) : SenderKey

    /** `wa:<package>:<value>` or `call:<value>`; [parse] round-trips it. */
    fun storageId(): String = when (this) {
        is WhatsApp -> "$WA_PREFIX$packageName:$value"
        is Call -> "$CALL_PREFIX$value"
    }

    companion object {
        internal val WHATSAPP_PACKAGES = setOf(
            PerAppPreferencesManager.WHATSAPP,
            PerAppPreferencesManager.WHATSAPP_BUSINESS,
        )

        /**
         * The key a [storageId] encodes, or null for an unknown prefix, a
         * non-WhatsApp package or an empty value. The value may contain ':'
         * (package names never do, so only the first separator splits).
         */
        fun parse(id: String): SenderKey? = when {
            id.startsWith(WA_PREFIX) -> {
                val rest = id.removePrefix(WA_PREFIX)
                val separator = rest.indexOf(':')
                if (separator < 0) {
                    null
                } else {
                    val packageName = rest.substring(0, separator)
                    val value = rest.substring(separator + 1)
                    if (packageName in WHATSAPP_PACKAGES && value.isNotEmpty()) {
                        WhatsApp(packageName, value)
                    } else {
                        null
                    }
                }
            }
            id.startsWith(CALL_PREFIX) ->
                id.removePrefix(CALL_PREFIX).takeIf { it.isNotEmpty() }?.let { SenderKey.Call(it) }
            else -> null
        }
    }
}

/** Normalizes a raw phone label to E.164 for a country, or null when it cannot. */
fun interface PhoneNormalizer {
    fun toE164(raw: String, countryIso: String?): String?
}

/** The platform's libphonenumber-backed formatter (null for an unparseable or invalid number). */
object FrameworkPhoneNormalizer : PhoneNormalizer {
    override fun toE164(raw: String, countryIso: String?): String? {
        if (countryIso.isNullOrBlank()) return null
        return PhoneNumberUtils.formatNumberToE164(raw, countryIso.uppercase(Locale.ROOT))
    }
}

/**
 * The device's country (SIM, then network, then locale), lowercase ISO 3166
 * alpha-2, or null. The SIM country is the home country and stays put; the
 * network country follows the phone while roaming, so a national-format call
 * label normalized under it would drift to another key and stop matching
 * the user's sender rule (and History or a re-run deriving it later would
 * disagree with the original).
 */
object DeviceCountry {
    fun iso(context: Context): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return pick(
            sim = telephony?.simCountryIso,
            network = telephony?.networkCountryIso,
            locale = Locale.getDefault().country,
        )
    }

    /** The first non-blank of [sim], [network], [locale], lowercased. */
    internal fun pick(sim: String?, network: String?, locale: String?): String? =
        sequenceOf(sim, network, locale)
            .firstOrNull { !it.isNullOrBlank() }
            ?.lowercase(Locale.ROOT)
}

/** Pure derivation of [SenderKey]s from what each origin knows about its sender. */
object SenderKeys {

    private val PHONE_SEPARATORS = Regex("[\\s\\-.()]")
    private val PHONE_SHAPE = Regex("^\\+?\\d{5,15}$")
    private val WHITESPACE_RUN = Regex("\\s+")

    /** A WhatsApp key for the two WhatsApp sources; null for the watched folder or no name. */
    fun forVoiceNote(source: VoiceNoteSource, senderName: String?): SenderKey? =
        when (source) {
            VoiceNoteSource.WHATSAPP, VoiceNoteSource.WHATSAPP_BUSINESS ->
                whatsApp(source.packageName, senderName)
            VoiceNoteSource.WATCHED_FOLDER -> null
        }

    /** A WhatsApp key only when [sourcePackage] is one of the two WhatsApp clients. */
    fun forShare(sourcePackage: String?, senderName: String?): SenderKey? =
        whatsApp(sourcePackage, senderName)

    /**
     * A call key from a recorder file-name label. A phone-shaped label goes to
     * E.164 when [phone] can, else to its digits (a leading '+' kept); any
     * other label is a contact name, normalized like a WhatsApp name.
     */
    fun forCallRecording(label: String?, countryIso: String?, phone: PhoneNormalizer): SenderKey? {
        if (label.isNullOrBlank()) return null
        val compact = label.replace(PHONE_SEPARATORS, "")
        if (PHONE_SHAPE.matches(compact)) {
            return SenderKey.Call(phone.toE164(label, countryIso) ?: compact)
        }
        return normalizeName(label)?.let { SenderKey.Call(it) }
    }

    /**
     * The key a recorded row's origin facts derive (a History row, or the
     * suspension re-run's extras): a voice-note row from one of the two
     * WhatsApp packages as that source's sender, a folder-watch row as a
     * call recording (its stored label IS the parsed caller, normalized for
     * [countryIso]), any other row as a share (a key only for a WhatsApp
     * package). The one derivation both the History row menu and the re-run
     * use, so the rule a row opens is the rule its re-run applies.
     */
    fun forRow(
        sourceKind: String?,
        sourcePackage: String?,
        senderName: String?,
        countryIso: String?,
        phone: PhoneNormalizer = FrameworkPhoneNormalizer,
    ): SenderKey? = when (sourceKind) {
        SourceKind.VOICE_NOTE ->
            ScopeProfileSource.voiceNoteSourceForPackage(sourcePackage)?.let { forVoiceNote(it, senderName) }
        SourceKind.FOLDER_WATCH -> forCallRecording(senderName, countryIso, phone)
        else -> forShare(sourcePackage, senderName)
    }

    /** NFC, trimmed, whitespace runs collapsed; case kept on purpose. Blank gives null. */
    internal fun normalizeName(raw: String): String? =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .trim()
            .replace(WHITESPACE_RUN, " ")
            .takeIf { it.isNotEmpty() }

    private fun whatsApp(packageName: String?, senderName: String?): SenderKey? {
        if (packageName == null || packageName !in SenderKey.WHATSAPP_PACKAGES) return null
        val name = senderName?.let(::normalizeName) ?: return null
        return SenderKey.WhatsApp(packageName, name)
    }
}
