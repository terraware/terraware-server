package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingDateRequestSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingDateRequestsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingDateRequestsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val clock = TestClock()
  private val store: PlantingDateRequestsStore by lazy {
    PlantingDateRequestsStore(clock, dslContext)
  }

  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var substratumId: SubstratumId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertPlantingSite()
    insertOrganizationUser(role = Role.Manager)
    insertStratum()
    plantingSeasonId = insertPlantingSeason()
    substratumId = insertSubstratum()
    speciesId = insertSpecies()
  }

  @Nested
  inner class Create {
    @Test
    fun `inserts a new request with no species`() {
      val date = LocalDate.of(2026, 1, 1)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate(date = date)

      store.create(scheduledPlantingDateId, plantingSeasonId)

      assertTableEquals(
          PlantingDateRequestsRecord(
              date = date,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              scheduledPlantingDateId = scheduledPlantingDateId,
              statusId = PlantingDateRequestStatus.Pending,
          )
      )

      assertTableEmpty(PLANTING_DATE_REQUEST_SPECIES)
    }

    @Test
    fun `inserts a new request with species`() {
      val date = LocalDate.of(2026, 1, 2)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate(date = date)
      insertScheduledPlantingDateSpecies(quantity = 5)
      val speciesId2 = insertSpecies()
      insertScheduledPlantingDateSpecies(quantity = 10)

      val newRequestId = store.create(scheduledPlantingDateId, plantingSeasonId)

      assertTableEquals(
          PlantingDateRequestsRecord(
              date = date,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              scheduledPlantingDateId = scheduledPlantingDateId,
              statusId = PlantingDateRequestStatus.Pending,
          )
      )

      assertTableEquals(
          listOf(
              PlantingDateRequestSpeciesRecord(
                  plantingDateRequestId = newRequestId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
              ),
              PlantingDateRequestSpeciesRecord(
                  plantingDateRequestId = newRequestId,
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  quantity = 10,
              ),
          )
      )
    }

    @Test
    fun `includes optional notes`() {
      val date = LocalDate.of(2026, 1, 3)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate(date = date)

      store.create(scheduledPlantingDateId, plantingSeasonId, "notes")

      assertTableEquals(
          PlantingDateRequestsRecord(
              date = date,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              notes = "notes",
              scheduledPlantingDateId = scheduledPlantingDateId,
              statusId = PlantingDateRequestStatus.Pending,
          )
      )

      assertTableEmpty(PLANTING_DATE_REQUEST_SPECIES)
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      assertThrows<PlantingSeasonClosedException> {
        store.create(scheduledPlantingDateId, plantingSeasonId)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.create(scheduledPlantingDateId, plantingSeasonId)
      }
    }

    @Test
    fun `throws PlantingSeasonDateRequestExistsException when date already exists`() {
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      insertPlantingDateRequest()

      assertThrows<PlantingSeasonDateRequestExistsException> {
        store.create(
            scheduledPlantingDateId,
            plantingSeasonId,
        )
      }
    }
  }
}
