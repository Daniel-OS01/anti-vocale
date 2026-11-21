package com.antivocale.app.transcription

/**
 * Whether a request's backend override should stay resident after it ends.
 *
 * An override request unloads its model when it finishes so a one-off share
 * alias does not leave a non-default model in memory. A batch routed to one
 * overridden model (a folder scope, a bulk redo) would then pay a cold load
 * per file, so the drain keeps the model when the NEXT queued request names
 * the same override. The next request's `ensureBackendLoaded` still checks
 * id, variant and language, so a kept model is only reused when it matches.
 *
 * Null means "uses the global backend": a null [next] gives false, so the
 * override model is unloaded and the global one reloads lazily, as before.
 */
object BackendRetention {
    fun keepLoaded(current: String?, next: String?): Boolean =
        current != null && next != null && current == next
}
