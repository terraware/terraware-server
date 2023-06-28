package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId

/**
 * Published when the species of an accession is modified. Not published when the species of an
 * accession is set initially, only when it is subsequently modified.
 */
data class AccessionSpeciesChangedEvent(
    val accessionId: AccessionId,
    val oldSpeciesId: SpeciesId,
    val newSpeciesId: SpeciesId,
)
