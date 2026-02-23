package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator

val TRACKED_ACCUMULATED_METRICS =
    listOf(
        AutoCalculatedIndicator.HectaresPlanted,
        AutoCalculatedIndicator.TreesPlanted,
        AutoCalculatedIndicator.SpeciesPlanted,
    )

data class MetricProgressModel(
    val metric: AutoCalculatedIndicator,
    val progress: Int,
)
