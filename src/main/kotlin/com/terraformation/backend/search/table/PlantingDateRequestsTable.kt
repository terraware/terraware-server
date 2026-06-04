package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingDateRequestsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSeasonScheduledDates.asSingleValueSublist(
              "scheduledPlantingDate",
              PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(SCHEDULED_PLANTING_DATES.ID),
          ),
          plantingDateRequestSpecies.asMultiValueSublist(
              "plantingDateRequestSpecies",
              PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID.eq(
                  PLANTING_DATE_REQUEST_SPECIES.SCHEDULED_PLANTING_DATE_ID
              ),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          dateField("date", PLANTING_DATE_REQUESTS.DATE),
          textField("notes", PLANTING_DATE_REQUESTS.NOTES),
          enumField("status", PLANTING_DATE_REQUESTS.STATUS_ID),
      )

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_DATE_REQUESTS.DATE.desc())

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSeasons

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SEASONS)
        .on(
            PLANTING_DATE_REQUESTS.scheduledPlantingDates.PLANTING_SEASON_ID.eq(PLANTING_SEASONS.ID)
        )
  }
}
