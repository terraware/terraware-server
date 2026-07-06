package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.SpeciesNativity
import java.time.LocalDate

data class SourcedSpeciesNativity(
    val speciesNativity: SpeciesNativity,
    val datasetType: ExternalDatasetType?,
    val datasetDate: LocalDate?,
)
