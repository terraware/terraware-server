package com.terraformation.backend.species.api

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.species.model.SpeciesDataSourceModel
import java.time.LocalDate

data class SpeciesDataSourcePayload(
    val datasetDate: LocalDate,
    val datasetType: ExternalDatasetType,
)

fun SpeciesDataSourceModel.toPayload() = SpeciesDataSourcePayload(datasetDate, datasetType)
