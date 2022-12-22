package com.terraformation.backend.db

import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.tables.records.TimeZonesRecord
import com.terraformation.backend.db.default_schema.tables.references.TIME_ZONES
import com.terraformation.backend.log.perClassLogger
import java.time.ZoneId
import javax.annotation.PostConstruct
import javax.inject.Named
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Ensures that the `time_zones` table has all the time zone names recognized by the Java standard
 * library. Java uses the IANA tz database.
 */
@ConditionalOnProperty(TerrawareServerConfig.DAILY_TASKS_ENABLED_PROPERTY, matchIfMissing = true)
@Named
class TimeZonePopulator(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  @PostConstruct
  fun populateTimeZonesTable() {
    val existingValues =
        dslContext
            .select(TIME_ZONES.TIME_ZONE)
            .from(TIME_ZONES)
            .fetchSet(TIME_ZONES.TIME_ZONE.asNonNullable())
    val desiredValues = ZoneId.getAvailableZoneIds().toSet()

    val valuesToInsert = desiredValues.minus(existingValues)
    val valuesToDelete = existingValues.minus(desiredValues)

    if (valuesToInsert.isNotEmpty()) {
      val timeZonesInserted =
          dslContext
              .insertInto(TIME_ZONES, TIME_ZONES.TIME_ZONE)
              .valuesOfRecords(valuesToInsert.map { TimeZonesRecord(it) })
              .onConflictDoNothing()
              .execute()
      log.info("Inserted $timeZonesInserted new time zones")
    }

    if (valuesToDelete.isNotEmpty()) {
      val timeZonesDeleted =
          dslContext
              .deleteFrom(TIME_ZONES)
              .where(TIME_ZONES.TIME_ZONE.`in`(valuesToDelete))
              .execute()

      log.info("Deleted $timeZonesDeleted time zones")
    }
  }
}
