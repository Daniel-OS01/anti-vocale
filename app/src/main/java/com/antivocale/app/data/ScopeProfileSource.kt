package com.antivocale.app.data

import android.util.Log
import com.antivocale.app.transcription.LanguageSupport
import com.antivocale.app.transcription.LanguageSupportLookup
import com.antivocale.app.transcription.ResolvedProfile
import com.antivocale.app.transcription.ScopeChain
import com.antivocale.app.transcription.ScopeKind
import com.antivocale.app.transcription.TranscriptionProfileResolver
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** R6: what an enqueue origin knows about one request ([ScopeProfileSource.resolve]). */
data class OriginFacts(
    /** The per-source default pin (R5); WATCHED_FOLDER has none. */
    val voiceNoteSource: VoiceNoteSource? = null,
    /** A watched folder's tree uri (the folder profile and its rules). */
    val folderTreeUri: String? = null,
    /** The file's path relative to the folder root (null = root). */
    val subPath: String? = null,
    /** Opaque sender key; filled by a later feature (R6 passes null). */
    val senderStorageId: String? = null,
    /** The request's own explicit profile (share alias, Tasker). */
    val explicit: TranscriptionProfile? = null,
)

/**
 * R6: the ONE boundary that turns an enqueue origin's facts into a
 * [ResolvedProfile]. Each origin (folder watch, voice notes, the
 * suspension re-run) resolves here once, before [com.antivocale.app.service.InferenceEnqueue],
 * and the result rides the request as the backend and language overrides.
 *
 * Reads every scope from its owner ([ScheduledFolderStore] for the folder
 * profile and subfolder rules, [ScopeProfileStore] for sender profiles and
 * the language→model map, [PreferencesManager] for the per-source voice-note
 * pins and the global backend), then hands the pure
 * [TranscriptionProfileResolver] the chain. The resolver's rules live
 * there; nothing here decides precedence.
 *
 * The resolver's `support` predicate is synchronous while
 * [LanguageSupportLookup.support] is suspend: every (backend, language)
 * pair the resolver could ask about is pre-resolved here (each candidate
 * backend × each scope language), and the predicate reads that table.
 */
@Singleton
class ScopeProfileSource @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val folderStore: ScheduledFolderStore,
    private val scopeProfileStore: ScopeProfileStore,
    private val activeModelRepository: ActiveModelRepository,
    private val languageSupportLookup: LanguageSupportLookup,
) {

    suspend fun resolve(facts: OriginFacts): ResolvedProfile {
        val folder = facts.folderTreeUri?.let { folderStore.byUri(it) }
        val senderProfile = facts.senderStorageId?.let { scopeProfileStore.senderProfile(it) }
        val chain = ScopeChain(
            explicit = facts.explicit,
            sender = senderProfile,
            subfolder = folder?.let { SubfolderPaths.longestMatch(it.subfolderRules, facts.subPath) },
            folder = folder?.profile,
            sourceDefault = sourceDefault(facts.voiceNoteSource),
        )
        val resolved = if (chain.hasNoProfile()) {
            // Fast path: nothing to resolve, so no installed-set or
            // language-map read.
            ResolvedProfile(backendOverride = null, languageOverride = null, decidedBy = ScopeKind.GLOBAL)
        } else {
            resolveChain(chain)
        }
        // Never paths, folder names or sender ids/names: kinds and ids only.
        Log.i(
            TAG,
            "scope_profile_resolved source=${sourceLabel(facts)} decidedBy=${resolved.decidedBy} " +
                "backend=${resolved.backendOverride ?: "-"} language=${resolved.languageOverride ?: "-"} " +
                "warning=${resolved.warning?.name ?: "-"} " +
                "sender_rule=${if (senderProfile != null) "matched" else "none"}",
        )
        return resolved
    }

    private suspend fun resolveChain(chain: ScopeChain): ResolvedProfile {
        val languageModels = scopeProfileStore.languageModels()
        val globalBackendId = preferencesManager.transcriptionBackend.first()
        val installed: Set<String> =
            activeModelRepository.installedBackendsFlow.first().mapTo(HashSet()) { it.backendId }
        val supportTable = supportTable(chain, languageModels, globalBackendId)
        return TranscriptionProfileResolver.resolve(
            chain = chain,
            languageModels = languageModels,
            globalBackendId = globalBackendId,
            isLoadable = { id -> id.isNotBlank() && id in installed },
            support = { backend, language ->
                // Every pair the resolver can ask about is in the table by
                // construction (see supportTable); a miss is a broken
                // invariant, never a silent default.
                supportTable[backend to language]
                    ?: error("scope profile support pair was not pre-resolved")
            },
        )
    }

    /**
     * The resolver asks `support(B, L)` at most once, for the scope
     * language L and the backend B it picked: an explicit backend, a scope
     * pin, `languageModels[L]`, or the global backend. Pre-resolving every
     * candidate backend × every scope language covers that call without
     * re-deriving the resolver's walk here.
     */
    private suspend fun supportTable(
        chain: ScopeChain,
        languageModels: Map<String, String>,
        globalBackendId: String,
    ): Map<Pair<String, String>, LanguageSupport> {
        val scopes = listOf(chain.sender, chain.subfolder, chain.folder, chain.sourceDefault)
        val languages = scopes
            .mapNotNull { it?.language.nonBlank() }
            .filter { it != TranscriptionProfile.LANGUAGE_AUTO }
            .toSet()
        if (languages.isEmpty()) return emptyMap()
        val backends = buildSet {
            add(globalBackendId)
            chain.explicit?.backendId.nonBlank()?.let { add(it) }
            scopes.forEach { scope -> scope?.backendId.nonBlank()?.let { add(it) } }
            languages.forEach { language -> languageModels[language].nonBlank()?.let { add(it) } }
        }
        val table = HashMap<Pair<String, String>, LanguageSupport>()
        for (backend in backends) {
            for (language in languages) {
                table[backend to language] = languageSupportLookup.support(backend, language)
            }
        }
        return table
    }

    private suspend fun sourceDefault(source: VoiceNoteSource?): TranscriptionProfile? {
        val pin = when (source) {
            VoiceNoteSource.WHATSAPP -> preferencesManager.voiceNoteBackendId.first()
            VoiceNoteSource.WHATSAPP_BUSINESS -> preferencesManager.voiceNoteBusinessBackendId.first()
            VoiceNoteSource.WATCHED_FOLDER, null -> null
        }
        return TranscriptionProfile.of(pin, null).takeUnless { it.isEmpty }
    }

    private fun ScopeChain.hasNoProfile(): Boolean =
        listOf(explicit, sender, subfolder, folder, sourceDefault).all { it == null || it.isEmpty }

    private fun sourceLabel(facts: OriginFacts): String = when {
        facts.voiceNoteSource != null -> facts.voiceNoteSource.name
        facts.folderTreeUri != null -> "FOLDER_WATCH"
        facts.explicit != null -> "EXPLICIT"
        else -> "NONE"
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeUnless { it.isEmpty() }

    companion object {
        private const val TAG = "ScopeProfileSource"

        /** A History row's origin package back to its voice-note source:
         *  only the two WhatsApp packages map (the watched-folder source
         *  stamps no package). */
        fun voiceNoteSourceForPackage(packageName: String?): VoiceNoteSource? =
            packageName?.let { pkg ->
                VoiceNoteSource.entries.firstOrNull { it.packageName != null && it.packageName == pkg }
            }
    }
}
