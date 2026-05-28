package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSeasonScheduledDatesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SCHEDULED_PLANTING_DATES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSeasons.asSingleValueSublist(
              "plantingSeason",
              SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID),
          ),
          scheduledPlantingDateSpeciesTable.asMultiValueSublist(
              "scheduledDateSpecies",
              SCHEDULED_PLANTING_DATES.ID.eq(
                  SCHEDULED_PLANTING_DATE_SPECIES.SCHEDULED_PLANTING_DATE_ID
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("date", SCHEDULED_PLANTING_DATES.DATE),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(SCHEDULED_PLANTING_DATES.DATE.desc())

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSeasons

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SEASONS)
        .on(SCHEDULED_PLANTING_DATES.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID))
  }
}
