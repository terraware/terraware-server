package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingSubzonePopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_POPULATION_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.eq(SPECIES.ID),
          ),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzone",
              PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField(
              "plantsSinceLastObservation",
              PLANTING_SUBZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION,
          ),
          integerField("totalPlants", PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SUBZONES)
        .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID))
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID,
            PLANTING_SUBZONE_POPULATIONS.SPECIES_ID,
        )
}
