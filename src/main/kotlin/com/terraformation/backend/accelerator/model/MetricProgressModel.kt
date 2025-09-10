package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.SystemMetric

val TRACKED_ACCUMULATED_METRICS =
    listOf(
        SystemMetric.HectaresPlanted,
        SystemMetric.TreesPlanted,
        SystemMetric.SpeciesPlanted,
    )

data class MetricProgressModel(
    val metric: SystemMetric,
    val progress: Int,
)
