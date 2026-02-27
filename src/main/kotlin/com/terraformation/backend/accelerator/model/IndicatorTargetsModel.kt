package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.ProjectIndicatorId
import java.math.BigDecimal

data class YearlyIndicatorTargetModel(
    val target: Number?,
    val year: Number,
)

data class IndicatorTargetsModel<ID : Any>(
    val indicatorId: ID,
    val baseline: BigDecimal?,
    val endOfProjectTarget: BigDecimal?,
    val yearlyTargets: List<YearlyIndicatorTargetModel>,
)

typealias ProjectIndicatorTargetsModel = IndicatorTargetsModel<ProjectIndicatorId>

typealias CommonIndicatorTargetsModel = IndicatorTargetsModel<CommonIndicatorId>

typealias AutoCalculatedIndicatorTargetsModel = IndicatorTargetsModel<AutoCalculatedIndicator>
