package com.terraformation.backend.species.event

import com.terraformation.backend.db.default_schema.SpeciesId

/** Published when a new species is created or updated via a bulk data import. */
data class SpeciesImportedEvent(
    /** True if this was an update to an existing, non-deleted species. */
    val alreadyExisted: Boolean,
    val speciesId: SpeciesId,
)
