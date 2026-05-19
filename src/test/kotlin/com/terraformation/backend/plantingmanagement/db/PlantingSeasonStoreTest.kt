package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonsRecord
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSeasonStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: PlantingSeasonStore by lazy { PlantingSeasonStore(clock, dslContext) }

  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertPlantingSite()
    insertOrganizationUser(role = Role.Manager)
    plantingSiteId = inserted.plantingSiteId
  }

  @Nested
  inner class Create {
    @Test
    fun `inserts planting season and returns new ID`() {
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.Upcoming,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `sets status to Active when current date is within season range`() {
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      clock.instant = startDate.atStartOfDay().toInstant(ZoneOffset.UTC)

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.Active,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `sets status to PastEndDate when season has already ended`() {
      val startDate = LocalDate.of(2024, 1, 1)
      val endDate = LocalDate.of(2024, 3, 31)
      clock.instant = endDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2024",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2024",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.PastEndDate,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `uses planting site timezone to determine today when calculating status`() {
      // 03:00 UTC on Jan 1 is still Dec 31 in America/New_York (UTC-5)
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      clock.instant = startDate.atTime(3, 0).toInstant(ZoneOffset.UTC)
      plantingSiteId = insertPlantingSite(timeZone = ZoneId.of("America/New_York"))

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.Upcoming,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `falls back to organization timezone when planting site has no timezone`() {
      // 03:00 UTC on Jan 1 is still Dec 31 in America/New_York (UTC-5)
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      clock.instant = startDate.atTime(3, 0).toInstant(ZoneOffset.UTC)
      val orgId = insertOrganization(timeZone = ZoneId.of("America/New_York"))
      insertOrganizationUser(organizationId = orgId, role = Role.Manager)
      plantingSiteId = insertPlantingSite(organizationId = orgId)

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.Upcoming,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `falls back to UTC when neither planting site nor organization has a timezone`() {
      // 03:00 UTC on Jan 1 is Jan 1 in UTC, so the season is Active
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      clock.instant = startDate.atTime(3, 0).toInstant(ZoneOffset.UTC)

      val id =
          store.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              endDate = endDate,
              statusId = PlantingSeasonStatus.Active,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `throws exception if no permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.create(
            NewPlantingSeasonModel(
                endDate = LocalDate.of(2025, 3, 31),
                name = "Spring 2025",
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.of(2025, 1, 1),
            )
        )
      }
    }

    @Test
    fun `throws exception if season with same name already exists for planting site`() {
      insertPlantingSeason(name = "Spring 2025", plantingSiteId = plantingSiteId)

      assertThrows<PlantingSeasonExistsException> {
        store.create(
            NewPlantingSeasonModel(
                endDate = LocalDate.of(2025, 3, 31),
                name = "Spring 2025",
                plantingSiteId = plantingSiteId,
                startDate = LocalDate.of(2025, 1, 1),
            )
        )
      }
    }
  }

  @Nested
  inner class FetchById {
    @Test
    fun `returns model with all fields`() {
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      val id =
          insertPlantingSeason(
              name = "Spring 2025",
              startDate = startDate,
              endDate = endDate,
              status = PlantingSeasonStatus.Upcoming,
          )

      val result = store.fetchById(id)

      assertEquals(
          ExistingPlantingSeasonModel(
              endDate = endDate,
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = startDate,
              status = PlantingSeasonStatus.Upcoming,
          ),
          result,
      )
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when user is not a member of the organization`() {
      val id = insertPlantingSeason()
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> { store.fetchById(id) }
    }
  }
}
