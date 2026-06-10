package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingDateRequestStatus
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingDateRequestSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.PlantingDateRequestsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_DATE_REQUEST_SPECIES
import com.terraformation.backend.nursery.event.WithdrawalAssociatedWithPlantingDateRequestEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesCreatedEvent
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingDateRequestsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: PlantingDateRequestsStore by lazy {
    PlantingDateRequestsStore(clock, dslContext, eventPublisher, SeasonHelper(dslContext))
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var substratumId: SubstratumId
  private lateinit var substratumHistoryId: SubstratumHistoryId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0)
    insertOrganizationUser(role = Role.Manager)
    insertStratum()
    plantingSeasonId = insertPlantingSeason()
    substratumId = insertSubstratum()
    substratumHistoryId = inserted.substratumHistoryId
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

      assertTableEquals(
          listOf(
              PlantingDateRequestSpeciesRecord(
                  scheduledPlantingDateId = scheduledPlantingDateId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
              ),
              PlantingDateRequestSpeciesRecord(
                  scheduledPlantingDateId = scheduledPlantingDateId,
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
    fun `publishes created events for the request and its species`() {
      val date = LocalDate.of(2026, 1, 2)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate(date = date)
      insertScheduledPlantingDateSpecies(quantity = 5)

      store.create(scheduledPlantingDateId, plantingSeasonId, "notes")

      eventPublisher.assertEventPublished(
          PlantingDateRequestCreatedEvent(
              date = date,
              notes = "notes",
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              scheduledPlantingDateId = scheduledPlantingDateId,
              status = PlantingDateRequestStatus.Pending,
          )
      )
      eventPublisher.assertEventPublished(
          PlantingDateRequestSpeciesCreatedEvent(
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              quantity = 5,
              scheduledPlantingDateId = scheduledPlantingDateId,
              speciesId = speciesId,
              stratumName = "S1",
              substratumHistoryId = substratumHistoryId,
              substratumId = substratumId,
              substratumName = "1",
          )
      )
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

  @Nested
  inner class Update {
    @Test
    fun `updates the request and species`() {
      val oldDate = LocalDate.EPOCH
      val newDate = LocalDate.EPOCH.plusDays(1)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate(date = newDate)
      insertScheduledPlantingDateSpecies(quantity = 10)
      insertPlantingDateRequest(date = oldDate, notes = "old notes")
      insertPlantingDateRequestSpecies(quantity = 5)

      clock.instant = Instant.ofEpochSecond(100)
      store.update(scheduledPlantingDateId, plantingSeasonId, "new notes")

      assertTableEquals(
          PlantingDateRequestsRecord(
              date = newDate,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              notes = "new notes",
              scheduledPlantingDateId = scheduledPlantingDateId,
              statusId = PlantingDateRequestStatus.Pending,
          )
      )

      assertTableEquals(
          PlantingDateRequestSpeciesRecord(
              scheduledPlantingDateId = scheduledPlantingDateId,
              substratumId = substratumId,
              speciesId = speciesId,
              quantity = 10,
          )
      )
    }

    @Test
    fun `deletes species no longer in scheduled planting date`() {
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      insertPlantingDateRequest()
      insertPlantingDateRequestSpecies()

      store.update(scheduledPlantingDateId, plantingSeasonId)

      assertTableEmpty(PLANTING_DATE_REQUEST_SPECIES)
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      insertPlantingDateRequest()

      assertThrows<PlantingSeasonClosedException> {
        store.update(scheduledPlantingDateId, plantingSeasonId)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      insertPlantingDateRequest()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.update(scheduledPlantingDateId, plantingSeasonId)
      }
    }

    @Test
    fun `throws PlantingSeasonDateRequestNotFoundException when request doesn't exist`() {
      val scheduledPlantingDateId = insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonDateRequestNotFoundException> {
        store.update(scheduledPlantingDateId, plantingSeasonId)
      }
    }
  }

  @Nested
  inner class UpdateRequestStatus {
    private lateinit var scheduledPlantingDateId: ScheduledPlantingDateId

    @BeforeEach
    fun setUp() {
      insertFacility()
      scheduledPlantingDateId = insertPlantingSeasonScheduledDate()
      insertPlantingDateRequest(status = PlantingDateRequestStatus.Pending)
    }

    @Test
    fun `marks request Fulfilled when every species is fully withdrawn`() {
      val speciesId2 = insertSpecies()
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)
      insertPlantingDateRequestSpecies(speciesId = speciesId2, quantity = 10)

      withdraw(species = speciesId, readyQuantity = 5)
      withdraw(species = speciesId2, readyQuantity = 10)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Fulfilled, statusOf())
    }

    @Test
    fun `marks request Pending when no species has positive net withdrawn`() {
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Pending, statusOf())
    }

    @Test
    fun `marks request Partial when one species is met but another is untouched`() {
      val speciesId2 = insertSpecies()
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)
      insertPlantingDateRequestSpecies(speciesId = speciesId2, quantity = 10)

      withdraw(species = speciesId, readyQuantity = 5)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Partial, statusOf())
    }

    @Test
    fun `marks request Partial when a species is partially withdrawn`() {
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 10)

      withdraw(species = speciesId, readyQuantity = 4)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Partial, statusOf())
    }

    @Test
    fun `marks request Fulfilled when a species is over-withdrawn`() {
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)

      withdraw(species = speciesId, readyQuantity = 8)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Fulfilled, statusOf())
    }

    @Test
    fun `sums requested quantities across substrata per species`() {
      val substratumId2 = insertSubstratum()
      insertPlantingDateRequestSpecies(
          speciesId = speciesId,
          substratumId = substratumId,
          quantity = 3,
      )
      insertPlantingDateRequestSpecies(
          speciesId = speciesId,
          substratumId = substratumId2,
          quantity = 4,
      )

      withdraw(species = speciesId, readyQuantity = 7)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Fulfilled, statusOf())
    }

    @Test
    fun `treats an undo withdrawal as subtracting from the net withdrawn quantity`() {
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)

      val originalWithdrawalId = withdraw(species = speciesId, readyQuantity = 5)
      withdraw(
          species = speciesId,
          readyQuantity = -5,
          purpose = WithdrawalPurpose.Undo,
          undoesWithdrawalId = originalWithdrawalId,
      )

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Pending, statusOf())
    }

    @Test
    fun `ignores withdrawn species that are not part of the request`() {
      val unrequestedSpeciesId = insertSpecies()
      insertPlantingDateRequestSpecies(speciesId = speciesId, quantity = 5)

      withdraw(species = unrequestedSpeciesId, readyQuantity = 5)

      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Pending, statusOf())
    }

    @Test
    fun `marks a request with no species Pending`() {
      store.on(WithdrawalAssociatedWithPlantingDateRequestEvent(scheduledPlantingDateId))

      assertEquals(PlantingDateRequestStatus.Pending, statusOf())
    }

    private fun statusOf(id: ScheduledPlantingDateId = scheduledPlantingDateId) =
        plantingDateRequestsDao.fetchOneByScheduledPlantingDateId(id)!!.statusId

    /** Inserts a batch + withdrawal + batch withdrawal tied to the request under test. */
    private fun withdraw(
        species: SpeciesId,
        readyQuantity: Int,
        purpose: WithdrawalPurpose = WithdrawalPurpose.OutPlant,
        undoesWithdrawalId: WithdrawalId? = null,
    ): WithdrawalId {
      val batchId = insertBatch(speciesId = species)
      val withdrawalId =
          insertNurseryWithdrawal(
              plantingSeasonId = plantingSeasonId,
              scheduledPlantingDateRequestId = scheduledPlantingDateId,
              purpose = purpose,
              undoesWithdrawalId = undoesWithdrawalId,
          )
      insertBatchWithdrawal(
          batchId = batchId,
          withdrawalId = withdrawalId,
          readyQuantityWithdrawn = readyQuantity,
      )
      return withdrawalId
    }
  }
}
