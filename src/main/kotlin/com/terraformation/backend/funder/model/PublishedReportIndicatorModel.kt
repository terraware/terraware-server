package com.terraformation.backend.funder.model

import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import java.math.BigDecimal

data class PublishedCumulativeIndicatorProgressModel(
    val quarter: ReportQuarter,
    val value: BigDecimal,
)

data class PublishedReportIndicatorModel<ID : Any>(
    val baseline: BigDecimal? = null,
    val category: IndicatorCategory,
    val classId: IndicatorClass,
    val currentYearProgress: List<PublishedCumulativeIndicatorProgressModel>? = null,
    val description: String?,
    val endOfProjectTarget: BigDecimal? = null,
    val indicatorId: ID,
    val level: IndicatorLevel,
    val name: String,
    val previousYearCumulativeTotal: BigDecimal? = null,
    val progressNotes: String?,
    val projectsComments: String?,
    val refId: String,
    val status: ReportIndicatorStatus?,
    val target: BigDecimal?,
    val unit: String?,
    val value: BigDecimal?,
)
