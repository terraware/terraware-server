package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingZoneId
import java.math.BigDecimal

data class SpeciesDensityModel(
    val speciesId: SpeciesId,
    val density: BigDecimal,
)

data class OptionalSpeciesDensityModel(
    val speciesId: SpeciesId,
    val density: BigDecimal?,
)

data class SiteT0DataModel(
    val plantingSiteId: PlantingSiteId,
    val survivalRateIncludesTempPlots: Boolean,
    val plots: List<PlotT0DataModel> = emptyList(),
    val zones: List<ZoneT0TempDataModel> = emptyList(),
)

data class PlotSpeciesModel(
    val monitoringPlotId: MonitoringPlotId,
    val species: List<OptionalSpeciesDensityModel> = emptyList(),
)

data class PlotT0DataModel(
    val monitoringPlotId: MonitoringPlotId,
    val observationId: ObservationId? = null,
    val densityData: List<SpeciesDensityModel> = emptyList(),
)

data class ZoneT0TempDataModel(
    val plantingZoneId: PlantingZoneId,
    val densityData: List<SpeciesDensityModel> = emptyList(),
)

data class SpeciesDensityChangedModel(
    val speciesId: SpeciesId,
    val previousDensity: BigDecimal? = null,
    val newDensity: BigDecimal? = null,
) {
  init {
    require(previousDensity != null || newDensity != null) {
      "Either previousDensity or newDensity must be non-null."
    }
  }
}

data class PlotT0DensityChangedModel(
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotNumber: Long? = null,
    val speciesDensityChanges: Set<SpeciesDensityChangedModel>,
)

data class ZoneT0TempDensityChangedModel(
    val plantingZoneId: PlantingZoneId,
    val zoneName: String? = null,
    val speciesDensityChanges: Set<SpeciesDensityChangedModel>,
)

data class SpeciesDensityChangedEventModel(
    val speciesId: SpeciesId,
    val speciesScientificName: String,
    val previousDensity: BigDecimal? = null,
    val newDensity: BigDecimal? = null,
) {
  companion object {
    fun of(speciesChangeModel: SpeciesDensityChangedModel, scientificName: String) =
        SpeciesDensityChangedEventModel(
            speciesId = speciesChangeModel.speciesId,
            speciesScientificName = scientificName,
            previousDensity = speciesChangeModel.previousDensity,
            newDensity = speciesChangeModel.newDensity,
        )
  }
}

data class PlotT0DensityChangedEventModel(
    val monitoringPlotId: MonitoringPlotId,
    val monitoringPlotNumber: Long,
    val speciesDensityChanges: List<SpeciesDensityChangedEventModel>,
)

data class ZoneT0DensityChangedEventModel(
    val plantingZoneId: PlantingZoneId,
    val zoneName: String,
    val speciesDensityChanges: List<SpeciesDensityChangedEventModel>,
)
