package com.antivocale.app.data

/**
 * R3: the persisted origin of a History row (`LogEntity.sourceKind`).
 *
 * Distinct from `InferenceService.EXTRA_SOURCE`, the transient behavior
 * switch: a re-run sends `SOURCE_RETRANSCRIBE` there but copies the
 * ORIGINAL row's kind here, so a re-transcribed folder-watch row keeps
 * its folder group. Values are stable persistence tokens (Room column and
 * the backup archive); never rename one.
 */
object SourceKind {
    const val SHARE = "share"
    const val BROWSE = "browse"
    const val FOLDER_WATCH = "folder_watch"
    const val VOICE_NOTE = "voice_note"
    const val OPENTRANSCRIBE = "opentranscribe"
    const val AUTOMATION = "automation"

    val ALL: Set<String> = setOf(SHARE, BROWSE, FOLDER_WATCH, VOICE_NOTE, OPENTRANSCRIBE, AUTOMATION)
}
