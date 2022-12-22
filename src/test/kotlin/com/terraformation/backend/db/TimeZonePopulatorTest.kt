package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.pojos.TimeZonesRow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class TimeZonePopulatorTest : DatabaseTest() {
  private val populator by lazy { TimeZonePopulator(dslContext) }

  private val userId = UserId(1000)

  private val invalidTimeZone = "Bogus/Nowhere"
  private val validTimeZone = "America/New_York"

  @Test
  fun `inserts time zones that are not already in the database`() {
    // A time zone that is extremely unlikely to be removed from the tz database
    populator.populateTimeZonesTable()

    assertEquals(
        TimeZonesRow(validTimeZone),
        timeZonesDao.fetchOneByTimeZone(validTimeZone),
        "Time zone should have been inserted")
  }

  @Test
  fun `deletes time zones that are no longer in the Java time zones list`() {
    timeZonesDao.insert(TimeZonesRow(invalidTimeZone))

    insertUser(userId, timeZone = invalidTimeZone)

    populator.populateTimeZonesTable()

    assertNull(
        timeZonesDao.fetchOneByTimeZone(invalidTimeZone), "Time zone should have been deleted")
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
