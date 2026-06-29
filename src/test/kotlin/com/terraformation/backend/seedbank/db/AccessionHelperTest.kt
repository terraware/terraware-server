package com.terraformation.backend.seedbank.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.seedbank.model.AccessionModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class AccessionHelperTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private lateinit var parentStore: ParentStore
  private lateinit var helper: AccessionHelper
  private lateinit var facilityId: FacilityId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    facilityId = insertFacility()
    insertOrganizationUser(role = Role.Admin)
    parentStore = ParentStore(dslContext)
    helper = AccessionHelper(parentStore)
  }

  @Nested
  inner class EffectiveCollectedDate {
    @Test
    fun `collectedTime null returns collectedDate`() {
      val date = LocalDate.of(2024, 3, 20)
      val accession = AccessionModel(clock = clock, facilityId = facilityId, collectedDate = date)

      assertEquals(date, helper.effectiveCollectedDate(accession, facilityId))
    }

    @Test
    fun `collectedTime and collectedDate both null returns null`() {
      val accession = AccessionModel(clock = clock, facilityId = facilityId)

      assertNull(helper.effectiveCollectedDate(accession, facilityId))
    }

    @Test
    fun `derives date from collectedTime using user timezone`() {
      // Jan 15 at 10pm in America/New_York
      val collectedTime = Instant.parse("2024-01-16T03:00:00Z")
      val userId = insertUser(timeZone = ZoneId.of("America/New_York"))
      insertOrganizationUser(userId = userId, role = Role.Admin)
      switchToUser(userId)

      val accession =
          AccessionModel(clock = clock, facilityId = facilityId, collectedTime = collectedTime)

      assertEquals(LocalDate.of(2024, 1, 15), helper.effectiveCollectedDate(accession, facilityId))
    }

    @Test
    fun `derives date from collectedTime using facility timezone when user has none`() {
      // Jan 15 at 10pm in America/New_York
      val collectedTime = Instant.parse("2024-01-16T03:00:00Z")
      val orgId = insertOrganization(timeZone = ZoneId.of("America/New_York"))
      val facilityId = insertFacility(organizationId = orgId)
      insertOrganizationUser(organizationId = orgId, role = Role.Admin)

      val accession =
          AccessionModel(clock = clock, facilityId = facilityId, collectedTime = collectedTime)

      assertEquals(LocalDate.of(2024, 1, 15), helper.effectiveCollectedDate(accession, facilityId))
    }

    @Test
    fun `derives date from collectedTime using UTC when no timezone configured`() {
      val collectedTime = Instant.parse("2024-01-16T03:00:00Z")

      val accession =
          AccessionModel(clock = clock, facilityId = facilityId, collectedTime = collectedTime)

      assertEquals(LocalDate.of(2024, 1, 16), helper.effectiveCollectedDate(accession, facilityId))
    }
  }

  @Nested
  inner class CollectionTimeZone {
    @Test
    fun `returns user timezone when set`() {
      val userId = insertUser(timeZone = ZoneId.of("America/Chicago"))
      insertOrganizationUser(userId = userId, role = Role.Admin)
      switchToUser(userId)

      assertEquals(ZoneId.of("America/Chicago"), helper.collectionTimeZone(facilityId))
    }

    @Test
    fun `returns facility timezone when user has no timezone`() {
      val orgId = insertOrganization(timeZone = ZoneId.of("Europe/London"))
      val fId = insertFacility(organizationId = orgId)
      insertOrganizationUser(organizationId = orgId, role = Role.Admin)

      assertEquals(ZoneId.of("Europe/London"), helper.collectionTimeZone(fId))
    }
  }
}
