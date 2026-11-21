package com.antivocale.app.data

private fun isDisclosureId(id: String): Boolean =
    id.indexOf('.') in 1 until id.lastIndex && '=' !in id

internal fun requireDisclosureId(id: String) {
    require(isDisclosureId(id)) {
        "Disclosure IDs must be namespaced and cannot contain '='"
    }
}

internal fun decodeDisclosureState(entries: Set<String>): Map<String, Boolean> = buildMap {
    for (entry in entries) {
        val id = entry.dropLast(2)
        if (!isDisclosureId(id)) continue
        when {
            entry.endsWith("=1") -> put(id, true)
            entry.endsWith("=0") -> put(id, false)
        }
    }
}
