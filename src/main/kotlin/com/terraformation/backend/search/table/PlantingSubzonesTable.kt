package com.terraformation.backend.search.table

import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTINGS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SUMMARIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONE_POPULATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.search.SearchTable
import com.terraformation.backend.search.SublistField
import com.terraformation.backend.search.field.SearchField
import org.jooq.Record
import org.jooq.SelectJoinStep
import org.jooq.TableField
import org.jooq.impl.DSL

class PlantingSubzonesTable(private val tables: SearchTables) : SearchTable() {
  override val primaryKey: TableField<out Record, out Any?>
    get() = PLANTING_SUBZONES.ID

  override val sublists: List<SublistField> by lazy {
    with(tables) {
      listOf(
          plantingSubzoneHistories.asMultiValueSublist(
              "histories",
              PLANTING_SUBZONES.ID.eq(PLANTING_SUBZONE_HISTORIES.PLANTING_SUBZONE_ID),
          ),
          plantings.asMultiValueSublist(
              "plantings",
              PLANTING_SUBZONES.ID.eq(PLANTINGS.PLANTING_SUBZONE_ID),
          ),
          plantingSites.asSingleValueSublist(
              "plantingSite",
              PLANTING_SUBZONES.PLANTING_SITE_ID.eq(PLANTING_SITE_SUMMARIES.ID),
          ),
          plantingZones.asSingleValueSublist(
              "plantingZone",
              PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID),
          ),
          plantingSubzonePopulations.asMultiValueSublist(
              "populations",
              PLANTING_SUBZONES.ID.eq(PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID),
          ),
          monitoringPlots.asMultiValueSublist(
              "monitoringPlots",
              PLANTING_SUBZONES.ID.eq(MONITORING_PLOTS.PLANTING_SUBZONE_ID),
          ),
      )
    }
  }

  override val fields: List<SearchField> =
      listOf(
          geometryField("boundary", PLANTING_SUBZONES.BOUNDARY),
          timestampField("createdTime", PLANTING_SUBZONES.CREATED_TIME),
          textField("fullName", PLANTING_SUBZONES.FULL_NAME),
          idWrapperField("id", PLANTING_SUBZONES.ID) { PlantingSubzoneId(it) },
          timestampField("modifiedTime", PLANTING_SUBZONES.MODIFIED_TIME),
          textField("name", PLANTING_SUBZONES.NAME),
          timestampField("observedTime", PLANTING_SUBZONES.OBSERVED_TIME),
          timestampField("plantingCompletedTime", PLANTING_SUBZONES.PLANTING_COMPLETED_TIME),
          bigDecimalField(
              "totalPlants",
              DSL.field(
                  DSL.select(DSL.sum(PLANTING_SUBZONE_POPULATIONS.TOTAL_PLANTS))
                      .from(PLANTING_SUBZONE_POPULATIONS)
                      .where(
                          PLANTING_SUBZONE_POPULATIONS.PLANTING_SUBZONE_ID.eq(PLANTING_SUBZONES.ID)
                      )
              ),
          ),
      )

  override val inheritsVisibilityFrom: SearchTable
    get() = tables.plantingZones

  override fun <T : Record> joinForVisibility(query: SelectJoinStep<T>): SelectJoinStep<T> {
    return query.join(PLANTING_ZONES).on(PLANTING_SUBZONES.PLANTING_ZONE_ID.eq(PLANTING_ZONES.ID))
  }
}
