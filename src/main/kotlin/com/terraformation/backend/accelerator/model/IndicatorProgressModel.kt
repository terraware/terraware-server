package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator

val TRACKED_ACCUMULATED_INDICATORS =
    listOf(
        AutoCalculatedIndicator.HectaresPlanted,
        AutoCalculatedIndicator.TreesPlanted,
        AutoCalculatedIndicator.SpeciesPlanted,
    )

data class IndicatorProgressModel(
    val indicator: AutoCalculatedIndicator,
    val progress: Int,
)
