package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import java.math.BigDecimal

data class SpeciesDensityModel(
    val speciesId: SpeciesId,
    val plotDensity: BigDecimal,
)

data class PlotT0DataModel(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId? = null,
    val densityData: List<SpeciesDensityModel> = emptyList(),
)

data class SpeciesDensityChangedModel(
    val speciesId: SpeciesId,
    val previousPlotDensity: BigDecimal? = null,
    val newPlotDensity: BigDecimal? = null,
) {
  init {
    require(previousPlotDensity != null || newPlotDensity != null) {
      "Either previousPlotDensity or newPlotDensity must be non-null."
    }
  }
}

data class PlotT0DensityChangedModel(
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotNumber: Long? = null,
    val speciesDensityChanges: Set<SpeciesDensityChangedModel>,
)

data class SpeciesDensityChangedEventModel(
    val speciesId: SpeciesId,
    val speciesScientificName: String,
    val previousPlotDensity: BigDecimal? = null,
    val newPlotDensity: BigDecimal? = null,
) {
  init {
    require(previousPlotDensity != null || newPlotDensity != null) {
      "Either previousPlotDensity or newPlotDensity must be non-null."
    }
  }

  companion object {
    fun of(speciesChangeModel: SpeciesDensityChangedModel, scientificName: String) =
        SpeciesDensityChangedEventModel(
            speciesId = speciesChangeModel.speciesId,
            speciesScientificName = scientificName,
            previousPlotDensity = speciesChangeModel.previousPlotDensity,
            newPlotDensity = speciesChangeModel.newPlotDensity,
        )
  }
}

data class PlotT0DensityChangedEventModel(
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotNumber: Long,
    val speciesDensityChanges: List<SpeciesDensityChangedEventModel>,
)
