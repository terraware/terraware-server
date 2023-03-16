package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSubzonePopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species", PLANTING_SUBZONE_POPULATIONS.SPECIES_ID.eq(SPECIES.ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SUBZONE_POPULATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plantingSubzones.asSingleValueSublist(
              "plantingSubzones",
              PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          longField("totalPlants", PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_SUBZONE_POPULATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // PLANTING_SITE_SUMMARIES will have already been referenced by joinForVisibility.
    return when (scope) {
      is OrganizationIdScope -> PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() =
        listOf(
            PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID,
            PLANTING_SUBZONE_POPULATIONS.SPECIES_ID)
}
