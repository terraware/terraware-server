package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator

data class ProjectAutoCalculatedIndicatorModel(
    val active: Boolean,
    val indicator: AutoCalculatedIndicator,
    val isPublishable: Boolean,
    val notes: String?,
    val primaryDataSource: String?,
    val refId: String?,
)
