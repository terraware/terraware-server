package com.terraformation.backend.search.table

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.tables.references.COUNTRIES
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.DELIVERIES
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Condition
import org.jooq.OrderField
import org.jooq.Record
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSitesTable(tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SITE_SUMMARIES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          countries.asSingleValueSublist(
              "country",
              PLANTING_SITE_SUMMARIES.COUNTRY_CODE.eq(COUNTRIES.CODE),
          ),
          deliveries.asMultiValueSublist(
              "deliveries",
              PLANTING_SITE_SUMMARIES.ID.eq(DELIVERIES.PLANTING_SITE_ID),
          ),
          monitoringPlots.asMultiValueSublist(
              "exteriorPlots",
              PLANTING_SITE_SUMMARIES.ID.eq(MONITORING_PLOTS.PLANTING_SITE_ID)
                  .and(MONITORING_PLOTS.PLANTING_SUBZONE_ID.isNull),
          ),
          plantingSiteHistories.asMultiValueSublist(
              "histories",
              PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID),
          ),
          monitoringPlots.asMultiValueSublist(
              "monitoringPlots",
              PLANTING_SITE_SUMMARIES.ID.eq(MONITORING_PLOTS.PLANTING_SITE_ID)
                  .and(MONITORING_PLOTS.PLANTING_SUBZONE_ID.isNotNull),
          ),
          observations.asMultiValueSublist(
              "observations",
              PLANTING_SITE_SUMMARIES.ID.eq(OBSERVATIONS.PLANTING_SITE_ID),
          ),
          organizations.asSingleValueSublist(
              "organization",
              PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.eq(ORGANIZATIONS.ID),
          ),
          plantingSeasons.asMultiValueSublist(
              "plantingSeasons",
              PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_SEASONS.PLANTING_SITE_ID),
          ),
          plantingZones.asMultiValueSublist(
              "plantingZones",
              PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_ZONES.PLANTING_SITE_ID),
          ),
          plantingSitePopulations.asMultiValueSublist(
              "populations",
              PLANTING_SITE_SUMMARIES.ID.eq(PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID),
          ),
          projects.asSingleValueSublist(
              "project",
              PLANTING_SITE_SUMMARIES.PROJECT_ID.eq(PROJECTS.ID),
              isRequired = false,
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SITE_SUMMARIES.BOUNDARY),
          timestampField("createdTime", PLANTING_SITE_SUMMARIES.CREATED_TIME),
          textField("description", PLANTING_SITE_SUMMARIES.DESCRIPTION),
          geometryField("exclusion", PLANTING_SITE_SUMMARIES.EXCLUSION),
          idWrapperField("id", PLANTING_SITE_SUMMARIES.ID) { PlantingSiteId(it) },
          timestampField("modifiedTime", PLANTING_SITE_SUMMARIES.MODIFIED_TIME),
          textField("name", PLANTING_SITE_SUMMARIES.NAME),
          longField("numPlantingZones", PLANTING_SITE_SUMMARIES.NUM_PLANTING_ZONES),
          longField("numPlantingSubzones", PLANTING_SITE_SUMMARIES.NUM_PLANTING_SUBZONES),
          zoneIdField("timeZone", PLANTING_SITE_SUMMARIES.TIME_ZONE),
          bigDecimalField(
              "totalPlants",
              DSL.field(
                  DSL.select(DSL.sum(PLANTING_SITE_POPULATIONS.TOTAL_PLANTS))
                      .from(PLANTING_SITE_POPULATIONS)
                      .where(
                          PLANTING_SITE_POPULATIONS.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID)
                      )
              ),
          ),
      )

  override fun conditionForVisibility(): Condition {
    return PLANTING_SITE_SUMMARIES.ORGANIZATION_ID.`in`(currentUser().organizationRoles.keys)
  }

  override val defaultOrderFields: List<OrderField<*>>
    get() = listOf(PLANTING_SITE_SUMMARIES.ID)
}
