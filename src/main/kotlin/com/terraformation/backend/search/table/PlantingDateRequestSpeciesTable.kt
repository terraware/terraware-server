package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATA
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingDateRequestSpeciesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_DATE_REQUEST_SPECIES.PLANTING_DATE_REQUEST_SPECIES_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingDateRequests.asSingleValueSublist(
              "plantingDateRequest",
              PLANTING_DATE_REQUEST_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(
                  PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID
              ),
          ),
          species.asSingleValueSublist(
              "species",
              PLANTING_DATE_REQUEST_SPECIES.SPECIES_ID.eq(SPECIES.ID),
          ),
          substrata.asSingleValueSublist(
              "substratum",
              PLANTING_DATE_REQUEST_SPECIES.SUBSTRATUM_ID.eq(SUBSTRATA.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField("quantity", PLANTING_DATE_REQUEST_SPECIES.QUANTITY),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingDateRequests

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_DATE_REQUESTS)
        .on(
            PLANTING_DATE_REQUEST_SPECIES.SCHEDULED_PLANTING_DATE_ID.eq(
                PLANTING_DATE_REQUESTS.SCHEDULED_PLANTING_DATE_ID
            )
        )
  }
}
