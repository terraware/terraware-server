package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.TimeZonesRow
import java.time.ZoneId
import org.jooq.Record
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class TimeZonePopulatorTest : DatabaseTest() {
  private val populator by lazy { TimeZonePopulator(dslContext) }

  private val userId = UserId(1000)

  private val invalidTimeZone = "Bogus/Nowhere"
  private val validTimeZone = ZoneId.of("America/New_York")

  @Test
  fun `inserts time zones that are not already in the database`() {
    populator.populateTimeZonesTable()

    assertEquals(
        TimeZonesRow(validTimeZone),
        timeZonesDao.fetchOneByTimeZone(validTimeZone),
        "Time zone should have been inserted")
  }

  @Test
  fun `deletes time zones that are no longer in the Java time zones list`() {
    insertUser(userId)

    // We can't create a ZoneId object with an invalid zone name, so need to do this with raw SQL
    // rather than a type-safe jOOQ query.
    dslContext.execute("INSERT INTO time_zones VALUES ('$invalidTimeZone')")
    dslContext.execute("UPDATE users SET time_zone = '$invalidTimeZone' WHERE id = $userId")

    populator.populateTimeZonesTable()

    assertEquals(
        emptyList<Record>(),
        dslContext.fetch("SELECT * FROM time_zones WHERE time_zone = '$invalidTimeZone'"))
    assertNull(
        usersDao.fetchOneById(userId)!!.timeZone,
        "User time zone should have been set to null by foreign key constraint")
  }

  @Test
  fun `leaves existing valid time zone names alone rather than deleting and inserting them`() {
    timeZonesDao.insert(TimeZonesRow(validTimeZone))

    insertUser(userId, timeZone = validTimeZone)

    populator.populateTimeZonesTable()

    assertEquals(
        TimeZonesRow(validTimeZone),
        timeZonesDao.fetchOneByTimeZone(validTimeZone),
        "Time zone should still be present")
    assertEquals(
        validTimeZone,
        usersDao.fetchOneById(userId)?.timeZone,
        "User should still have time zone (it wasn't set to null by the foreign key constraint)")
  }
}
