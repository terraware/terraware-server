package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonsRecord
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.NewPlantingSeasonModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import java.time.Instant
import java.time.LocalDate
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
  private val store: PlantingSeasonStore by lazy {
    PlantingSeasonStore(clock, dslContext, ParentStore(dslContext))
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
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
  inner class FetchList {
    @Test
    fun `returns all seasons for planting site`() {
      val id1 =
          insertPlantingSeason(
              name = "Spring 2025",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              status = PlantingSeasonStatus.Upcoming,
          )
      val id2 =
          insertPlantingSeason(
              name = "Fall 2025",
              startDate = LocalDate.of(2025, 9, 1),
              endDate = LocalDate.of(2025, 11, 30),
              status = PlantingSeasonStatus.Upcoming,
          )

      val result = store.fetchList(plantingSiteId)

      assertEquals(
          listOf(
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 3, 31),
                  id = id1,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.of(2025, 1, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 11, 30),
                  id = id2,
                  name = "Fall 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.of(2025, 9, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
          ),
          result,
      )
    }

    @Test
    fun `returns all seasons for organization`() {
      val id1 =
          insertPlantingSeason(
              name = "Spring 2025",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              status = PlantingSeasonStatus.Upcoming,
          )
      val id2 =
          insertPlantingSeason(
              name = "Fall 2025",
              startDate = LocalDate.of(2025, 9, 1),
              endDate = LocalDate.of(2025, 11, 30),
              status = PlantingSeasonStatus.Upcoming,
          )
      insertOrganization()
      insertOrganizationUser(role = Role.Manager)
      insertPlantingSite()
      insertPlantingSeason(
          name = "Other Org Season",
          startDate = LocalDate.of(2025, 1, 1),
          endDate = LocalDate.of(2025, 3, 31),
          status = PlantingSeasonStatus.Upcoming,
      )

      val result = store.fetchList(organizationId)

      assertEquals(
          listOf(
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 3, 31),
                  id = id1,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.of(2025, 1, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 11, 30),
                  id = id2,
                  name = "Fall 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.of(2025, 9, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
          ),
          result,
      )
    }

    @Test
    fun `returns empty list when no seasons exist for site`() {
      val result = store.fetchList(plantingSiteId)

      assertEquals(emptyList<ExistingPlantingSeasonModel>(), result)
    }

    @Test
    fun `returns empty list when no seasons exist for org`() {
      val result = store.fetchList(organizationId)

      assertEquals(emptyList<ExistingPlantingSeasonModel>(), result)
    }

    @Test
    fun `only returns seasons for the specified site`() {
      val id = insertPlantingSeason(name = "Spring 2025", plantingSiteId = plantingSiteId)
      insertPlantingSite()
      val otherSiteId = inserted.plantingSiteId
      insertPlantingSeason(name = "Spring 2025", plantingSiteId = otherSiteId)

      val result = store.fetchList(plantingSiteId)

      assertEquals(
          listOf(
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.EPOCH.plusDays(1),
                  id = id,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.EPOCH,
                  status = PlantingSeasonStatus.Upcoming,
              ),
          ),
          result,
      )
    }

    @Test
    fun `returns species targets for each season`() {
      val id1 =
          insertPlantingSeason(
              name = "Spring 2025",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              status = PlantingSeasonStatus.Upcoming,
          )
      val id2 =
          insertPlantingSeason(
              name = "Fall 2025",
              startDate = LocalDate.of(2025, 9, 1),
              endDate = LocalDate.of(2025, 11, 30),
              status = PlantingSeasonStatus.Upcoming,
          )
      insertStratum()
      val substratumId = insertSubstratum()
      val speciesId = insertSpecies()
      insertPlantingSeasonSpeciesTarget(plantingSeasonId = id1, quantity = 10)
      insertPlantingSeasonSpeciesTarget(plantingSeasonId = id2, quantity = 20)

      val result = store.fetchList(plantingSiteId)

      assertEquals(
          listOf(
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 3, 31),
                  id = id1,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  speciesTargets =
                      listOf(
                          PlantingSeasonSpeciesTargetModel(
                              substratumId = substratumId,
                              speciesId = speciesId,
                              quantity = 10,
                          )
                      ),
                  startDate = LocalDate.of(2025, 1, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
              ExistingPlantingSeasonModel(
                  endDate = LocalDate.of(2025, 11, 30),
                  id = id2,
                  name = "Fall 2025",
                  plantingSiteId = plantingSiteId,
                  speciesTargets =
                      listOf(
                          PlantingSeasonSpeciesTargetModel(
                              substratumId = substratumId,
                              speciesId = speciesId,
                              quantity = 20,
                          )
                      ),
                  startDate = LocalDate.of(2025, 9, 1),
                  status = PlantingSeasonStatus.Upcoming,
              ),
          ),
          result,
      )
    }

    @Test
    fun `throws PlantingSiteNotFoundException when user is not a member of the organization`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchList(plantingSiteId) }
    }

    @Test
    fun `throws OrganizationNotFoundException when user is not a member of the organization`() {
      deleteOrganizationUser()

      assertThrows<OrganizationNotFoundException> { store.fetchList(organizationId) }
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
    fun `returns species targets when they exist`() {
      val startDate = LocalDate.of(2025, 1, 1)
      val endDate = LocalDate.of(2025, 3, 31)
      val id =
          insertPlantingSeason(
              name = "Spring 2025",
              startDate = startDate,
              endDate = endDate,
              status = PlantingSeasonStatus.Upcoming,
          )
      insertStratum()
      val substratumId = insertSubstratum()
      val speciesId = insertSpecies()
      insertPlantingSeasonSpeciesTarget(plantingSeasonId = id, quantity = 42)

      val result = store.fetchById(id)

      assertEquals(
          ExistingPlantingSeasonModel(
              endDate = endDate,
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              speciesTargets =
                  listOf(
                      PlantingSeasonSpeciesTargetModel(
                          substratumId = substratumId,
                          speciesId = speciesId,
                          quantity = 42,
                      )
                  ),
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

  @Nested
  inner class Update {
    @Test
    fun `updates all fields and recalculates status`() {
      val id =
          insertPlantingSeason(
              name = "Old Name",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              status = PlantingSeasonStatus.PastEndDate,
          )
      val newStart = LocalDate.of(2025, 6, 1)
      val newEnd = LocalDate.of(2025, 8, 31)
      clock.instant = Instant.EPOCH.plusSeconds(60)

      store.update(id, "New Name", newStart, newEnd)

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "New Name",
              plantingSiteId = plantingSiteId,
              startDate = newStart,
              endDate = newEnd,
              statusId = PlantingSeasonStatus.Upcoming,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `sets status to Active when today falls within the new date range`() {
      val id =
          insertPlantingSeason(
              name = "Season",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 1, 31),
              status = PlantingSeasonStatus.Upcoming,
          )
      val newStart = LocalDate.of(2025, 1, 1)
      val newEnd = LocalDate.of(2025, 3, 31)
      clock.instant = newStart.atStartOfDay().toInstant(ZoneOffset.UTC)

      store.update(id, "Season", newStart, newEnd)

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Season",
              plantingSiteId = plantingSiteId,
              startDate = newStart,
              endDate = newEnd,
              statusId = PlantingSeasonStatus.Active,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `sets status to PastEndDate when new end date is in the past`() {
      val id =
          insertPlantingSeason(
              name = "Season",
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              status = PlantingSeasonStatus.Upcoming,
          )
      val newStart = LocalDate.of(2024, 1, 1)
      val newEnd = LocalDate.of(2024, 3, 31)
      clock.instant = newEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

      store.update(id, "Season", newStart, newEnd)

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Season",
              plantingSiteId = plantingSiteId,
              startDate = newStart,
              endDate = newEnd,
              statusId = PlantingSeasonStatus.PastEndDate,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when season does not exist`() {
      val nonExistentId = PlantingSeasonId(999999L)

      assertThrows<PlantingSeasonNotFoundException> {
        store.update(nonExistentId, "Name", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))
      }
    }

    @Test
    fun `throws AccessDeniedException when user has no update permission`() {
      val id = insertPlantingSeason()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.update(id, "New Name", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes the planting season row`() {
      val id = insertPlantingSeason()

      store.delete(id)

      assertThrows<PlantingSeasonNotFoundException> { store.fetchById(id) }
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when season does not exist`() {
      val nonExistentId = PlantingSeasonId(999999L)

      assertThrows<PlantingSeasonNotFoundException> { store.delete(nonExistentId) }
    }

    @Test
    fun `throws AccessDeniedException when user has no delete permission`() {
      val id = insertPlantingSeason()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.delete(id) }
    }
  }

  @Nested
  inner class Close {
    @Test
    fun `closes the planting season`() {
      val id = insertPlantingSeason(name = "Spring 2025")
      clock.instant = Instant.EPOCH.plusSeconds(60)

      store.close(id)

      assertTableEquals(
          PlantingSeasonsRecord(
              id = id,
              name = "Spring 2025",
              plantingSiteId = plantingSiteId,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              statusId = PlantingSeasonStatus.Closed,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when season does not exist`() {
      val nonExistentId = PlantingSeasonId(999999L)

      assertThrows<PlantingSeasonNotFoundException> { store.close(nonExistentId) }
    }

    @Test
    fun `throws AccessDeniedException when user has no update permission`() {
      val id = insertPlantingSeason()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.close(id) }
    }
  }
}
