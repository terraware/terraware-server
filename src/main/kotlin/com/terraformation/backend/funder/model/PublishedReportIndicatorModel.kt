package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus

data class PublishedReportIndicatorModel<ID : Any>(
    val category: IndicatorCategory,
    val classId: IndicatorClass?,
    val description: String?,
    val indicatorId: ID,
    val level: IndicatorLevel,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val target: Int?,
    val unit: String?,
    val value: Int?,
)
