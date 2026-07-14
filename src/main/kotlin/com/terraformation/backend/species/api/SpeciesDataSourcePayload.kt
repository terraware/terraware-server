package com.terraformation.backend.species.api

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import java.time.LocalDate

data class SpeciesDataSourcePayload(
    val datasetDate: LocalDate,
    val datasetType: ExternalDatasetType,
)
