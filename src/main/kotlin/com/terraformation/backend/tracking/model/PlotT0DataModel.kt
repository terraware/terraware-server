package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import java.math.BigDecimal

data class PlotT0DataModel(
    val monitoringPlotId: MonitoringPlotId,
    val speciesId: SpeciesId,
    val plotDensity: BigDecimal,
    val observationId: ObservationId? = null,
)
