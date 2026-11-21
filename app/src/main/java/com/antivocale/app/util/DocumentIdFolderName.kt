package com.antivocale.app.util

private val storageVolumeId = Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")

/** `/storage/emulated/<user>/`, `/sdcard/`, or `/storage/<volume-id>/`; the first alternative wins over the third. */
private val storageRootPrefix = Regex("""^/(?:storage/emulated/\d+|sdcard|storage/[^/]+)/""")

/** Returns the immediate parent for local SAF path IDs. Opaque provider IDs carry no folder. */
internal fun parentFolderNameFromDocumentId(documentId: String?): String? {
    val id = documentId ?: return null
    val volume = id.substringBefore(':')
    val suffix = id.substringAfter(':', missingDelimiterValue = "")
    val path = when {
        // A raw absolute path below a storage root reads like the relative
        // ids below: a file in the root itself has no folder (not "0").
        volume == "raw" && suffix.startsWith('/') ->
            storageRootPrefix.find(suffix)?.let { suffix.substring(it.range.last + 1) } ?: suffix
        volume == "primary" || volume == "home" || storageVolumeId.matches(volume) ->
            suffix.takeUnless { it.startsWith('/') } ?: return null
        else -> return null
    }
    if (path.substringAfterLast('/').isBlank()) return null
    return path.substringBeforeLast('/', missingDelimiterValue = "")
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
}
