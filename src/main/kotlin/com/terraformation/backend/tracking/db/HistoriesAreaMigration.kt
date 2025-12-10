package com.terraformation.backend.tracking.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.util.calculateAreaHectares
import com.terraformation.backend.util.differenceNullable
import jakarta.inject.Named
import java.math.BigDecimal
import org.jooq.DSLContext
import org.jooq.TableField
import org.locationtech.jts.geom.Geometry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class HistoriesAreaMigration(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  @EventListener
  fun populatePlantingSiteHistoriesAreas(event: ApplicationStartedEvent) {
    with(PLANTING_SITE_HISTORIES) { populateArea(ID, BOUNDARY, AREA_HA, EXCLUSION) }
    with(STRATUM_HISTORIES) { populateArea(ID, BOUNDARY, AREA_HA, plantingSiteHistories.EXCLUSION) }
    with(SUBSTRATUM_HISTORIES) {
      populateArea(ID, BOUNDARY, AREA_HA, stratumHistories.plantingSiteHistories.EXCLUSION)
    }
  }

  private fun <ID : Any> populateArea(
      idColumn: TableField<*, ID?>,
      boundaryColumn: TableField<*, Geometry?>,
      areaColumn: TableField<*, BigDecimal?>,
      exclusionColumn: TableField<*, Geometry?>,
  ) {
    val tableName = idColumn.table!!.name

    dslContext.transaction { _ ->
      dslContext
          .select(idColumn, boundaryColumn.asNonNullable(), exclusionColumn)
          .from(idColumn.table)
          .where(areaColumn.isNull)
          .forUpdate()
          .skipLocked()
          .fetch()
          .also { result ->
            if (result.isNotEmpty) {
              log.info("Populating areas for ${result.size} row(s) in $tableName")
            }
          }
          .forEach { (id, boundary, exclusion) ->
            try {
              dslContext
                  .update(idColumn.table)
                  .set(areaColumn, boundary.differenceNullable(exclusion).calculateAreaHectares())
                  .where(idColumn.eq(id))
                  .execute()
            } catch (e: Exception) {
              log.error("Unable to populate areas for $tableName row $id", e)
            }
          }
    }
  }
}
