package com.terraformation.backend.species.model

import com.terraformation.backend.db.default_schema.ExternalDatasetType
import java.time.LocalDate

data class SpeciesDataSourceModel(
    val datasetDate: LocalDate,
    val datasetType: ExternalDatasetType,
) {
  companion object {
    fun of(datasetDate: LocalDate?, datasetType: ExternalDatasetType?): SpeciesDataSourceModel? {
      return if (datasetDate != null && datasetType != null) {
        SpeciesDataSourceModel(datasetDate, datasetType)
      } else {
        null
      }
    }
  }
}
