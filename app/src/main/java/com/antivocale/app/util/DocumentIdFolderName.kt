package com.antivocale.app.util

private val storageVolumeId = Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")

/** Returns the immediate parent for local SAF path IDs. Opaque provider IDs carry no folder. */
internal fun parentFolderNameFromDocumentId(documentId: String?): String? {
    val id = documentId ?: return null
    val volume = id.substringBefore(':')
    val suffix = id.substringAfter(':', missingDelimiterValue = "")
    val path = when {
        volume == "raw" && suffix.startsWith('/') -> suffix
        volume == "primary" || volume == "home" || storageVolumeId.matches(volume) ->
            suffix.takeUnless { it.startsWith('/') } ?: return null
        else -> return null
    }
    if (path.substringAfterLast('/').isBlank()) return null
    return path.substringBeforeLast('/', missingDelimiterValue = "")
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
}
