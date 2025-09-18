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
    var speciesScientificName: String? = null,
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
    var monitoringPlotNumber: Long? = null,
    var speciesDensityChanges: List<SpeciesDensityChangedModel>,
)
