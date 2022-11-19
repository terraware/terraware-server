package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLOT_POPULATIONS
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

class PlotPopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLOT_POPULATIONS.PLOT_POPULATION_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist("species", PLOT_POPULATIONS.SPECIES_ID.eq(SPECIES.ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite", PLOT_POPULATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plots.asSingleValueSublist("plot", PLOT_POPULATIONS.PLOT_ID.eq(PLOTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          longField(
              "totalPlants", "Plot total plants", PLOT_POPULATIONS.TOTAL_PLANTS, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLOT_POPULATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
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
    get() = listOf(PLOT_POPULATIONS.PLOT_ID, PLOT_POPULATIONS.SPECIES_ID)
}
