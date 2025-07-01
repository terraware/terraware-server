package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportMetricStatus

data class PublishedReportMetricModel<ID : Any>(
    val component: MetricComponent,
    val description: String?,
    val metricId: ID,
    val name: String,
    val progressNotes: String?,
    val reference: String,
    val status: ReportMetricStatus?,
    val target: Int?,
    val type: MetricType,
    val underperformanceJustification: String?,
    val value: Int?,
    val unit: String?,
)
