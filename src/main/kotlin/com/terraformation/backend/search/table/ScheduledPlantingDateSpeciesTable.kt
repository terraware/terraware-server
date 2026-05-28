package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class ScheduledPlantingDateSpeciesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SCHEDULED_PLANTING_DATE_SPECIES.SCHEDULED_PLANTING_DATE_SPECIES_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSeasonScheduledDates.asSingleValueSublist(
              "scheduledDate",
              SCHEDULED_PLANTING_DATE_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(
                  SCHEDULED_PLANTING_DATES.ID
              ),
          ),
          species.asSingleValueSublist(
              "species",
              SCHEDULED_PLANTING_DATE_SPECIES.SPECIES_ID.eq(SPECIES.ID),
          ),
          substrata.asSingleValueSublist(
              "substratum",
              SCHEDULED_PLANTING_DATE_SPECIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("quantity", SCHEDULED_PLANTING_DATE_SPECIES.QUANTITY),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSeasonScheduledDates

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(SCHEDULED_PLANTING_DATES)
        .on(
            SCHEDULED_PLANTING_DATE_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(
                SCHEDULED_PLANTING_DATES.ID
            )
        )
  }
}
