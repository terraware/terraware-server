package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import java.math.BigDecimal

data class NurserySpeciesModel(val speciesId: SpeciesId, val density: BigDecimal)

data class PlotSpeciesModel(
    val monitoringPlotId: MonitoringPlotId,
    val species: List<NurserySpeciesModel> = emptyList(),
)
