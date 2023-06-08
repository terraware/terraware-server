package com.terraformation.backend.search.table

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONE_POPULATIONS
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField

class PlantingZonePopulationsTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          species.asSingleValueSublist(
              "species", PLANTING_ZONE_POPULATIONS.SPECIES_ID.eq(SPECIES.ID)),
          plantingZones.asSingleValueSublist(
              "plantingZone", PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID)),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          integerField(
              "plantsSinceLastObservation",
              PLANTING_ZONE_POPULATIONS.PLANTS_SINCE_LAST_OBSERVATION),
          integerField("totalPlants", PLANTING_ZONE_POPULATIONS.TOTAL_PLANTS, nullable = false),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingSites

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query
        .join(PLANTING_ZONES)
        .on(PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
        .join(PLANTING_SITE_SUMMARIES)
        .on(PLANTING_ZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID))
  }

  override fun conditionForOrganization(organizationId: OrganizationId): Condition {
    // We will have already joined with PLANTING_SITE_SUMMARIES for the visibility check.
    return PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(organizationId)
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_ZONE_POPULATIONS.PLANTING_ZONE_ID, PLANTING_ZONE_POPULATIONS.SPECIES_ID)
}
