package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportMetricStatus

data class PublishedReportMetricModel<ID : Any>(
    val component: IndicatorCategory,
    val description: String?,
    val metricId: ID,
    val name: String,
    val progressNotes: String?,
    val projectsComments: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val target: Int?,
    val type: IndicatorLevel,
    val value: Int?,
    val unit: String?,
)
