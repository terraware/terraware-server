package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingDateRequestsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUESTS
import com.terraformation.backend.plantingmanagement.db.PlantingDateRequestsStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonScheduledDatesStore
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PlantingSeasonScheduledDatesServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val service: PlantingSeasonScheduledDatesService by lazy {
    PlantingSeasonScheduledDatesService(
        dslContext,
        PlantingDateRequestsStore(clock, dslContext),
        PlantingSeasonScheduledDatesStore(
            clock,
            dslContext,
            eventPublisher,
            ParentStore(dslContext),
        ),
    )
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
    fun `does not insert request if createNurseryRequest is false`() {
      service.create(
          PlantingSeasonScheduledDateModel(
              date = LocalDate.EPOCH,
              plantingSeasonId = plantingSeasonId,
          ),
          createNurseryRequest = false,
      )

      assertTableEmpty(PLANTING_DATE_REQUESTS)
    }

    @Test
    fun `inserts request if createNurseryRequest is true`() {
      val scheduledPlantingDateId =
          service.create(
              PlantingSeasonScheduledDateModel(
                  date = LocalDate.EPOCH,
                  plantingSeasonId = plantingSeasonId,
              ),
              createNurseryRequest = true,
              nurseryRequestNotes = "Notes",
          )

      assertTableEquals(
          PlantingDateRequestsRecord(
              scheduledPlantingDateId = scheduledPlantingDateId,
              date = LocalDate.EPOCH,
              statusId = PlantingDateRequestStatus.Pending,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              notes = "Notes",
          )
      )
    }
  }
}
