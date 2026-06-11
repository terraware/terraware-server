package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.tracking.db.SubstratumNotFoundException
import jakarta.inject.Named
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class SeasonHelper(private val dslContext: DSLContext) {
  data class SubstratumInfo(
      val stratumName: String,
      val substratumName: String,
      val substratumHistoryId: SubstratumHistoryId,
  )

  /** Returns information about a collection of substrata for inclusion in persistent events. */
  fun fetchSubstrataInfo(
      substratumIds: Collection<SubstratumId>
  ): Map<SubstratumId, SubstratumInfo> {
    if (substratumIds.isEmpty()) return emptyMap()

    val latestHistoryIdField =
        DSL.field(
            DSL.select(DSL.max(SUBSTRATUM_HISTORIES.ID))
                .from(SUBSTRATUM_HISTORIES)
                .where(SUBSTRATUM_HISTORIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID))
        )

    val substrata =
        dslContext
            .select(SUBSTRATA.ID, SUBSTRATA.NAME, STRATA.NAME, latestHistoryIdField)
            .from(SUBSTRATA)
            .join(STRATA)
            .on(SUBSTRATA.STRATUM_ID.eq(STRATA.ID))
            .where(SUBSTRATA.ID.`in`(substratumIds))
            .fetchMap(SUBSTRATA.ID.asNonNullable()) { record ->
              SubstratumInfo(
                  stratumName = record.value3()!!,
                  substratumName = record[SUBSTRATA.NAME]!!,
                  substratumHistoryId = record.value4()!!,
              )
            }

    substratumIds.forEach { id ->
      if (id !in substrata) {
        throw SubstratumNotFoundException(id)
      }
    }

    return substrata
  }

  fun fetchSubstratumInfo(substratumId: SubstratumId): SubstratumInfo {
    return fetchSubstrataInfo(listOf(substratumId))[substratumId]
        ?: throw SubstratumNotFoundException(substratumId)
  }

  fun fetchPlantingSiteAndOrganization(
      plantingSeasonId: PlantingSeasonId
  ): Pair<PlantingSiteId, OrganizationId> {
    return dslContext
        .select(PLANTING_SITES.ID.asNonNullable(), PLANTING_SITES.ORGANIZATION_ID.asNonNullable())
        .from(PLANTING_SEASONS)
        .join(PLANTING_SITES)
        .on(PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
        .fetchOne()
        ?.let { it.value1() to it.value2() }
        ?: throw PlantingSeasonNotFoundException(plantingSeasonId)
  }

  fun <T> withLockedPlantingSeason(plantingSeasonId: PlantingSeasonId, func: () -> T): T {
    return dslContext.transactionResult { _ ->
      dslContext
          .selectOne()
          .from(PLANTING_SEASONS)
          .where(PLANTING_SEASONS.ID.eq(plantingSeasonId))
          .forUpdate()
          .fetchOne() ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

      func()
    }
  }

  fun <T> withLockedScheduledPlantingDate(
      scheduledPlantingDateId: ScheduledPlantingDateId,
      func: () -> T,
  ): T {
    return dslContext.transactionResult { _ ->
      dslContext
          .selectOne()
          .from(SCHEDULED_PLANTING_DATES)
          .where(SCHEDULED_PLANTING_DATES.ID.eq(scheduledPlantingDateId))
          .forUpdate()
          .fetchOne() ?: throw PlantingSeasonScheduledDateNotFoundException(scheduledPlantingDateId)

      func()
    }
  }

  fun validateSeasonNotClosed(plantingSeasonId: PlantingSeasonId) {
    with(PLANTING_SEASONS) {
      val status =
          dslContext
              .select(STATUS_ID)
              .from(PLANTING_SEASONS)
              .where(ID.eq(plantingSeasonId))
              .fetchOne(STATUS_ID) ?: throw PlantingSeasonNotFoundException(plantingSeasonId)

      if (status == PlantingSeasonStatus.Closed) {
        throw PlantingSeasonClosedException(plantingSeasonId)
      }
    }
  }
}
