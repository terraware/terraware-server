package com.terraformation.backend.tracking.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATION_SITE_RESULTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import jakarta.inject.Named
import java.math.BigDecimal
import java.math.RoundingMode
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class TrackingStatsStore(
    private val dslContext: DSLContext,
) {
  /**
   * Returns the aggregated area-weighted survival rate for all the planting sites associated with a
   * project.
   */
  fun getSurvivalRate(projectId: ProjectId): Int? {
    requirePermissions { readProject(projectId) }

    return getSurvivalRate(PLANTING_SITES.PROJECT_ID.eq(projectId))
  }

  /**
   * Returns the aggregated area-weighted survival rate for all the planting sites associated with
   * an organization.
   */
  fun getSurvivalRate(organizationId: OrganizationId): Int? {
    requirePermissions { readOrganization(organizationId) }

    return getSurvivalRate(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
  }

  private fun getSurvivalRate(plantingSitesCondition: Condition): Int? {
    val rowNumField = DSL.field("rownum", Int::class.java)
    val survivalRateField = DSL.field("survival_rate", Int::class.java)
    val survivalRateAreaField = DSL.field("survival_rate_area", BigDecimal::class.java)

    val resultsPartitionedBySite =
        DSL.select(
                OBSERVATION_SITE_RESULTS.SURVIVAL_RATE.`as`(survivalRateField),
                OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_AREA.`as`(survivalRateAreaField),
                DSL.rowNumber()
                    .over(
                        DSL.partitionBy(OBSERVATION_SITE_RESULTS.PLANTING_SITE_ID)
                            .orderBy(OBSERVATIONS.COMPLETED_TIME.desc())
                    )
                    .`as`(rowNumField),
            )
            .from(OBSERVATION_SITE_RESULTS)
            .join(OBSERVATIONS)
            .on(OBSERVATION_SITE_RESULTS.OBSERVATION_ID.eq(OBSERVATIONS.ID))
            .join(PLANTING_SITES)
            .on(OBSERVATIONS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
            .where(OBSERVATIONS.COMPLETED_TIME.isNotNull)
            .and(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE.isNotNull)
            .and(OBSERVATION_SITE_RESULTS.SURVIVAL_RATE_AREA.isNotNull)
            .and(plantingSitesCondition)

    val aggregateSurvivalRate =
        dslContext
            .select(
                DSL.sum(survivalRateField.mul(survivalRateAreaField))
                    .div(DSL.nullif(DSL.sum(survivalRateAreaField), BigDecimal.ZERO))
            )
            .from(resultsPartitionedBySite)
            .where(rowNumField.eq(1))
            .fetchOne()
            ?.value1()

    return aggregateSurvivalRate?.setScale(0, RoundingMode.HALF_UP)?.toInt()
  }
}
