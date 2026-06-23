package com.terraformation.backend.statistics.db

import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.statistics.PublicStatisticsModel
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Computes aggregate, non-sensitive statistics about the platform for display to unauthenticated
 * clients.
 *
 * The methods here intentionally do not call `currentUser` or `requirePermissions`: the data is
 * served from a public, unauthenticated endpoint, so there is no current user to check. Only
 * platform-wide aggregate values that don't reveal anything about a specific organization should be
 * returned from here.
 *
 * Organizations tagged with either [InternalTagIds.Internal] or [InternalTagIds.Testing], along
 * with their projects and planting sites, are excluded from every statistic so that
 * Terraformation's own internal organizations don't inflate the numbers.
 */
@Named
class PublicStatisticsStore(
    private val dslContext: DSLContext,
) {
  /** Subquery selecting the IDs of organizations that are tagged as internal to Terraformation. */
  private val internalOrganizationIds =
      DSL.select(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
          .from(ORGANIZATION_INTERNAL_TAGS)
          .where(
              ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.`in`(
                  InternalTagIds.Internal,
                  InternalTagIds.Testing,
              )
          )

  fun fetchStatistics(): PublicStatisticsModel {
    return PublicStatisticsModel(
        totalOrganizations = countOrganizations(),
        totalCountries = countCountries(),
        totalAreaUnderRestorationHa = sumPlantingSiteArea(),
    )
  }

  private fun countOrganizations(): Int {
    return dslContext
        .selectCount()
        .from(ORGANIZATIONS)
        .where(ORGANIZATIONS.ID.notIn(internalOrganizationIds))
        .fetchOne(0, Int::class.java) ?: 0
  }

  private fun countCountries(): Int {
    val organizationCountries =
        DSL.select(ORGANIZATIONS.COUNTRY_CODE)
            .from(ORGANIZATIONS)
            .where(ORGANIZATIONS.COUNTRY_CODE.isNotNull)
            .and(ORGANIZATIONS.ID.notIn(internalOrganizationIds))

    val projectCountries =
        DSL.select(PROJECTS.COUNTRY_CODE)
            .from(PROJECTS)
            .where(PROJECTS.COUNTRY_CODE.isNotNull)
            .and(PROJECTS.ORGANIZATION_ID.notIn(internalOrganizationIds))

    return dslContext.fetchCount(organizationCountries.union(projectCountries))
  }

  private fun sumPlantingSiteArea(): BigDecimal {
    return dslContext
        .select(DSL.sum(PLANTING_SITES.AREA_HA))
        .from(PLANTING_SITES)
        .where(PLANTING_SITES.ORGANIZATION_ID.notIn(internalOrganizationIds))
        .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
  }
}
