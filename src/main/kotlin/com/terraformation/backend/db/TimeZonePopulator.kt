package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.tables.records.TimeZonesRecord
import com.terraformation.backend.db.default_schema.tables.references.TIME_ZONES
import com.terraformation.backend.log.perClassLogger
import java.time.ZoneId
import javax.annotation.PostConstruct
import javax.inject.Named
import org.jooq.DSLContext

/**
 * Ensures that the `time_zones` table has all the time zone names recognized by the Java standard
 * library. Java uses the IANA tz database.
 */
@DisableIfNoDatabase
@Named
class TimeZonePopulator(private val dslContext: DSLContext) {
  private val log = perClassLogger()

  @PostConstruct
  fun populateTimeZonesTable() {
    val validZoneIds = ZoneId.getAvailableZoneIds().map { ZoneId.of(it) }.toSet()

    val timeZonesDeleted =
        dslContext.deleteFrom(TIME_ZONES).where(TIME_ZONES.TIME_ZONE.notIn(validZoneIds)).execute()

    val existingValues =
        dslContext
            .select(TIME_ZONES.TIME_ZONE)
            .from(TIME_ZONES)
            .fetchSet(TIME_ZONES.TIME_ZONE.asNonNullable())

    val valuesToInsert = validZoneIds.minus(existingValues)

    val timeZonesInserted =
        if (valuesToInsert.isNotEmpty()) {
          dslContext
              .insertInto(TIME_ZONES, TIME_ZONES.TIME_ZONE)
              .valuesOfRecords(valuesToInsert.map { TimeZonesRecord(it) })
              .onConflictDoNothing()
              .execute()
        } else {
          0
        }

    log.info("Inserted $timeZonesInserted and deleted $timeZonesDeleted time zones")
  }
}
