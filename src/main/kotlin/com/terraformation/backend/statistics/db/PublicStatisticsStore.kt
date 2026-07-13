package com.terraformation.backend.statistics.db

import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_INTERNAL_TAGS
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATION_USERS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.USERS
import com.terraformation.backend.db.nursery.tables.references.BATCHES
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.references.ACCESSIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
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
 * Organizations that are excluded from every statistic (along with their projects and planting
 * sites) so that Terraformation's own organizations don't inflate the numbers are:
 * - Organizations tagged with [InternalTagIds.Testing].
 * - Organizations that have an [Role.Owner] whose email address ends in "@terraformation.com"
 *   (case-insensitive), unless the organization is tagged with [InternalTagIds.Internal].
 */
@Named
class PublicStatisticsStore(
    private val dslContext: DSLContext,
) {
  /**
   * Maximum area of a planting site that we consider. Larger than this is probably just for
   * testing.
   */
  private val MAX_SITE_AREA_HA = BigDecimal("10000")
  private val MAX_SEEDS_IN_ACCESSION = 1_000_000
  private val MAX_SEEDLINGS_IN_BATCH = 1_000_000
  private val MAX_PLANTS_IN_PLANTING = 500_000

  private fun taggedOrganizationIds(internalTagId: InternalTagId) =
      DSL.select(ORGANIZATION_INTERNAL_TAGS.ORGANIZATION_ID)
          .from(ORGANIZATION_INTERNAL_TAGS)
          .where(ORGANIZATION_INTERNAL_TAGS.INTERNAL_TAG_ID.eq(internalTagId))

  /**
   * Subquery selecting the IDs of organizations that are excluded from public statistics because
   * they belong to Terraformation.
   */
  private val excludedOrganizationIds =
      taggedOrganizationIds(InternalTagIds.Testing)
          .union(
              DSL.select(ORGANIZATION_USERS.ORGANIZATION_ID)
                  .from(ORGANIZATION_USERS)
                  .join(USERS)
                  .on(ORGANIZATION_USERS.USER_ID.eq(USERS.ID))
                  .where(ORGANIZATION_USERS.ROLE_ID.eq(Role.Owner))
                  .and(USERS.EMAIL.likeIgnoreCase("%@terraformation.com"))
                  .and(
                      ORGANIZATION_USERS.ORGANIZATION_ID.notIn(
                          taggedOrganizationIds(InternalTagIds.Internal)
                      )
                  )
          )

  private val cachedStatistics: PublicStatisticsModel by lazy { computeStatistics() }

  fun fetchStatistics(): PublicStatisticsModel = cachedStatistics

  private fun computeStatistics(): PublicStatisticsModel {
    return PublicStatisticsModel(
        totalOrganizations = countOrganizations(),
        totalCountries = countCountries(),
        totalAreaUnderRestorationHa = sumPlantingSiteArea(),
        totalSeedsInStorage = sumSeedsInStorage(),
        totalSeedlingsInNurseries = sumSeedlingsInNurseries(),
        totalPlantings = countPlantings(),
    )
  }

  private fun countOrganizations(): Int {
    return dslContext
        .selectCount()
        .from(ORGANIZATIONS)
        .where(ORGANIZATIONS.ID.notIn(excludedOrganizationIds))
        .fetchOne(0, Int::class.java) ?: 0
  }

  private fun countCountries(): Int {
    val organizationCountries =
        DSL.select(ORGANIZATIONS.COUNTRY_CODE)
            .from(ORGANIZATIONS)
            .where(ORGANIZATIONS.COUNTRY_CODE.isNotNull)
            .and(ORGANIZATIONS.ID.notIn(excludedOrganizationIds))

    val projectCountries =
        DSL.select(PROJECTS.COUNTRY_CODE)
            .from(PROJECTS)
            .where(PROJECTS.COUNTRY_CODE.isNotNull)
            .and(PROJECTS.ORGANIZATION_ID.notIn(excludedOrganizationIds))

    return dslContext.fetchCount(organizationCountries.union(projectCountries))
  }

  private fun sumPlantingSiteArea(): BigDecimal {
    with(PLANTING_SITES) {
      return dslContext
          .select(DSL.sum(AREA_HA))
          .from(PLANTING_SITES)
          .where(ORGANIZATION_ID.notIn(excludedOrganizationIds))
          .and(AREA_HA.le(MAX_SITE_AREA_HA))
          .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
    }
  }

  private fun sumSeedsInStorage(): Long {
    with(ACCESSIONS) {
      return dslContext
          .select(DSL.sum(EST_SEED_COUNT))
          .from(ACCESSIONS)
          .join(FACILITIES)
          .on(FACILITY_ID.eq(FACILITIES.ID))
          .where(FACILITIES.ORGANIZATION_ID.notIn(excludedOrganizationIds))
          .and(STATE_ID.`in`(AccessionState.entries.filter { it.active }))
          .and(EST_SEED_COUNT.le(MAX_SEEDS_IN_ACCESSION))
          .fetchOne { (total) -> (total ?: BigDecimal.ZERO).toLong() } ?: 0L
    }
  }

  private fun sumSeedlingsInNurseries(): Long {
    with(BATCHES) {
      return dslContext
          .select(
              DSL.sum(
                  GERMINATING_QUANTITY.plus(ACTIVE_GROWTH_QUANTITY)
                      .plus(READY_QUANTITY)
                      .plus(HARDENING_OFF_QUANTITY)
              )
          )
          .from(BATCHES)
          .where(ORGANIZATION_ID.notIn(excludedOrganizationIds))
          .and(
              (GERMINATING_QUANTITY.plus(ACTIVE_GROWTH_QUANTITY)
                      .plus(READY_QUANTITY)
                      .plus(HARDENING_OFF_QUANTITY))
                  .le(MAX_SEEDLINGS_IN_BATCH)
          )
          .fetchOne(0, Long::class.java) ?: 0L
    }
  }

  private fun countPlantings(): Int {
    with(PLANTINGS) {
      return dslContext
          .select(DSL.sum(NUM_PLANTS))
          .from(PLANTINGS)
          .join(PLANTING_SITES)
          .on(PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
          .where(PLANTING_SITES.ORGANIZATION_ID.notIn(excludedOrganizationIds))
          .and(NUM_PLANTS.le(MAX_PLANTS_IN_PLANTING))
          .fetchOne(0, Int::class.java) ?: 0
    }
  }
}
