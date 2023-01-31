package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.tables.references.FACILITIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import com.terraformation.backend.search.SearchScope
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTINGS.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          deliveries.asSingleValueSublist("delivery", PLANTINGS.DELIVERY_ID.eq(DELIVERIES.ID)),
          species.asSingleValueSublist("species", PLANTINGS.SPECIES_ID.eq(SPECIES.ID)),
          plantingSites.asSingleValueSublist(
              "plantingSite", PLANTINGS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)),
          plots.asSingleValueSublist("plot", PLANTINGS.PLOT_ID.eq(PLOTS.ID)),
      )
    }
  }

  override val fields: List<SearchField> by lazy {
    listOf(
        timestampField("createdTime", PLANTINGS.CREATED_TIME, nullable = false),
        idWrapperField("id", PLANTINGS.ID) { PlantingId(it) },
        textField("notes", PLANTINGS.NOTES),
        integerField("numPlants", PLANTINGS.NUM_PLANTS, nullable = false),
        enumField("type", PLANTINGS.PLANTING_TYPE_ID, nullable = false),
    )
  }

  override val inheritsVisibilityFrom: SearchTable = tables.deliveries

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(DELIVERIES).on(PLANTINGS.DELIVERY_ID.eq(DELIVERIES.ID))
  }

  override fun conditionForScope(scope: SearchScope): Condition {
    // We will have already joined with DELIVERIES for the visibility check.
    return when (scope) {
      is OrganizationIdScope -> DELIVERIES.plantingSites.ORGANIZATION_ID.eq(scope.organizationId)
      is FacilityIdScope ->
          DELIVERIES.plantingSites.ORGANIZATION_ID.eq(
              DSL.select(FACILITIES.ORGANIZATION_ID)
                  .from(FACILITIES)
                  .where(FACILITIES.ID.eq(scope.facilityId)))
    }
  }
}
