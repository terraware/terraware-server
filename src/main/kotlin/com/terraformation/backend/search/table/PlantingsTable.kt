package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTINGS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          deliveries.asSingleValueSublist("delivery", PLANTINGS.DELIVERY_ID.eq(DELIVERIES.ID)),
          species.asSingleValueSublist("species", PLANTINGS.SPECIES_ID.eq(SPECIES.ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTINGS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone",
              PLANTINGS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField("createdTime", PLANTINGS.CREATED_TIME),
        idWrapperField("id", PLANTINGS.ID) { PlantingId(it) },
        textField("notes", PLANTINGS.NOTES),
        integerField("numPlants", PLANTINGS.NUM_PLANTS),
        enumField("type", PLANTINGS.PLANTING_TYPE_ID),
    )
  }

  override val inheritsVisibilityFrom: SearchTable = tables.deliveries

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(DELIVERIES).on(PLANTINGS.DELIVERY_ID.eq(DELIVERIES.ID))
  }
}
