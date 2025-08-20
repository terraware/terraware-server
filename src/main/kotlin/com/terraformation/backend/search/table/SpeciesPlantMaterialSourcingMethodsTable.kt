package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PLANT_MATERIAL_SOURCING_METHODS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class SpeciesPlantMaterialSourcingMethodsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = SPECIES_PLANT_MATERIAL_SOURCING_METHODS.SPECIES_PLANT_MATERIAL_SOURCING_METHOD_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species",
              SPECIES_PLANT_MATERIAL_SOURCING_METHODS.SPECIES_ID.eq(SPECIES.ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          enumField(
              "plantMaterialSourcingMethod",
              SPECIES_PLANT_MATERIAL_SOURCING_METHODS.PLANT_MATERIAL_SOURCING_METHOD_ID,
          ),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.species

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(SPECIES).on(SPECIES_PLANT_MATERIAL_SOURCING_METHODS.SPECIES_ID.eq(SPECIES.ID))
  }
}
