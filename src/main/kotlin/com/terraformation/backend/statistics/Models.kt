package com.terraformation.backend.statistics

import java.math.BigDecimal

data class PublicStatisticsModel(
    val totalOrganizations: Int,
    val totalCountries: Int,
    val totalAreaUnderRestorationHa: BigDecimal,
)
