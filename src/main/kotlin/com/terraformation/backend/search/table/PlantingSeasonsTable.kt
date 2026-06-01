package com.terraformation.backend.search.table

import com.terraformation.backend.db.nursery.tables.references.WITHDRAWAL_SUMMARIES
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_ALLOCATED_SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSeasonsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SEASONS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSeasonAllocatedSpeciesTable.asMultiValueSublist(
              "allocatedSpecies",
              PLANTING_SEASONS.ID.eq(PLANTING_SEASON_ALLOCATED_SPECIES.PLANTING_SEASON_ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSeasonSpeciesTargets.asMultiValueSublist(
              "speciesTargets",
              PLANTING_SEASONS.ID.eq(PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID),
          ),
          plantingSeasonScheduledDates.asMultiValueSublist(
              "scheduledDates",
              PLANTING_SEASONS.ID.eq(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID),
          ),
          nurseryWithdrawals.asMultiValueSublist(
              "withdrawals",
              PLANTING_SEASONS.ID.eq(WITHDRAWAL_SUMMARIES.PLANTING_SEASON_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("endDate", PLANTING_SEASONS.END_DATE),
          idWrapperField("id", PLANTING_SEASONS.ID) { PlantingSeasonId(it) },
          textField("name", PLANTING_SEASONS.NAME),
          enumField("status", PLANTING_SEASONS.STATUS_ID),
          dateField("startDate", PLANTING_SEASONS.START_DATE),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SEASONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }
}
