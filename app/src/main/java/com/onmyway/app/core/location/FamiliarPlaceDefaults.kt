package com.onmyway.app.core.location

import com.onmyway.app.core.model.Place

internal const val DEFAULT_FAMILIAR_PLACE_KEY = "default_familiar_place"

/** The alias is the source of truth; a stale alias never falls back to another saved place. */
internal fun defaultPlaceOrNull(alias: String?, places: Map<String, Place>): Place? =
    alias?.takeIf { it.isNotBlank() }?.let(places::get)

internal fun defaultAliasAfterRemoval(alias: String?, removed: String): String? =
    alias?.takeUnless { it == removed }

internal fun defaultAliasAfterRename(alias: String?, old: String, new: String): String? =
    if (alias == old) new else alias
