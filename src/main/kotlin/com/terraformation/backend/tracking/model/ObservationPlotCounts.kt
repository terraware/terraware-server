package com.terraformation.backend.tracking.model

data class ObservationPlotCounts(
    val totalIncomplete: Int,
    val totalPlots: Int,
    val totalUnclaimed: Int,
)
