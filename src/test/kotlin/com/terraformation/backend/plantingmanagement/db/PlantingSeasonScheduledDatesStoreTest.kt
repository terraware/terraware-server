package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.ScheduledPlantingDateSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ScheduledPlantingDatesRecord
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATES
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.plantingmanagement.ExistingPlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class PlantingSeasonScheduledDatesStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  private val clock = TestClock()
  private val store: PlantingSeasonScheduledDatesStore by lazy {
    PlantingSeasonScheduledDatesStore(clock, dslContext)
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
  inner class FetchList {
    @Test
    fun `returns empty list when no dates exist`() {
      val result = store.fetchList(plantingSeasonId)

      assertEquals(
          emptyList<ExistingPlantingSeasonScheduledDateModel>(),
          result,
          "No dates to fetch",
      )
    }

    @Test
    fun `returns dates for the specified season`() {
      val date1 = LocalDate.EPOCH
      val date2 = LocalDate.EPOCH.plusDays(1)
      val speciesId2 = insertSpecies()
      val scheduledDate1 = insertPlantingSeasonScheduledDate(date = date1)
      val scheduledDate2 = insertPlantingSeasonScheduledDate(date = date2)
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = scheduledDate1,
          speciesId = speciesId,
          quantity = 10,
      )
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = scheduledDate2,
          speciesId = speciesId2,
          quantity = 20,
      )

      insertPlantingSeason()
      val otherSeasonScheduledDate = insertPlantingSeasonScheduledDate(date = date1)
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = otherSeasonScheduledDate,
          speciesId = speciesId,
          quantity = 30,
      )

      val result = store.fetchList(plantingSeasonId)

      assertEquals(
          listOf(
              ExistingPlantingSeasonScheduledDateModel(
                  date = date2,
                  plantingSeasonId = plantingSeasonId,
                  scheduledPlantingDateId = scheduledDate2,
                  species =
                      listOf(
                          PlantingSeasonScheduledDateSpecies(
                              quantity = 20,
                              speciesId = speciesId2,
                              substratumId = substratumId,
                          )
                      ),
              ),
              ExistingPlantingSeasonScheduledDateModel(
                  date = date1,
                  plantingSeasonId = plantingSeasonId,
                  scheduledPlantingDateId = scheduledDate1,
                  species =
                      listOf(
                          PlantingSeasonScheduledDateSpecies(
                              quantity = 10,
                              speciesId = speciesId,
                              substratumId = substratumId,
                          )
                      ),
              ),
          ),
          result,
          "Dates for the correct season",
      )
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when user lacks permission`() {
      insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies()

      deleteOrganizationUser()
      assertThrows<PlantingSeasonNotFoundException> { store.fetchList(plantingSeasonId) }
    }
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns the specified scheduled date`() {
      val date1 = LocalDate.EPOCH
      val speciesId2 = insertSpecies()
      val scheduledDate = insertPlantingSeasonScheduledDate(date = date1)
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = scheduledDate,
          speciesId = speciesId,
          quantity = 10,
      )
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = scheduledDate,
          speciesId = speciesId2,
          quantity = 20,
      )

      val otherScheduledDate = insertPlantingSeasonScheduledDate(date = date1.plusDays(1))
      insertScheduledPlantingDateSpecies(
          scheduledPlantingDateId = otherScheduledDate,
          speciesId = speciesId,
          quantity = 30,
      )

      val result = store.fetch(plantingSeasonId, scheduledDate)

      assertEquals(
          ExistingPlantingSeasonScheduledDateModel(
              date = date1,
              plantingSeasonId = plantingSeasonId,
              scheduledPlantingDateId = scheduledDate,
              species =
                  listOf(
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 10,
                          speciesId = speciesId,
                          substratumId = substratumId,
                      ),
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 20,
                          speciesId = speciesId2,
                          substratumId = substratumId,
                      ),
                  ),
          ),
          result,
          "returns correct scheduled date",
      )
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when no date exists`() {
      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.fetch(plantingSeasonId, ScheduledPlantingDateId(99999L))
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when date does not belong to season`() {
      insertPlantingSeason()
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.fetch(plantingSeasonId, scheduledDateId)
      }
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when user lacks permission`() {
      val scheduledDate = insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies()

      deleteOrganizationUser()
      assertThrows<PlantingSeasonNotFoundException> { store.fetch(plantingSeasonId, scheduledDate) }
    }
  }

  @Nested
  inner class Create {
    @Test
    fun `inserts a new scheduled date with no species`() {
      val model =
          PlantingSeasonScheduledDateModel(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH,
          )

      store.create(model)

      assertTableEquals(
          ScheduledPlantingDatesRecord(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      assertTableEmpty(SCHEDULED_PLANTING_DATE_SPECIES)
    }

    @Test
    fun `inserts a new scheduled date with species`() {
      val substratumId2 = insertSubstratum()
      val speciesId2 = insertSpecies()

      val model =
          PlantingSeasonScheduledDateModel(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH.plusDays(1),
              species =
                  listOf(
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 5,
                          speciesId = speciesId,
                          substratumId = substratumId,
                      ),
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 10,
                          speciesId = speciesId2,
                          substratumId = substratumId,
                      ),
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 15,
                          speciesId = speciesId,
                          substratumId = substratumId2,
                      ),
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 20,
                          speciesId = speciesId2,
                          substratumId = substratumId2,
                      ),
                  ),
          )

      val scheduledDateId = store.create(model)

      assertTableEquals(
          ScheduledPlantingDatesRecord(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      assertTableEquals(
          listOf(
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 5,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId,
                  substratumId = substratumId,
              ),
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 10,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId2,
                  substratumId = substratumId,
              ),
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 15,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId,
                  substratumId = substratumId2,
              ),
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 20,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId2,
                  substratumId = substratumId2,
              ),
          )
      )
    }

    @Test
    fun `throws DuplicateKeyException when same species-substratum combination is added twice`() {
      assertThrows<DuplicateKeyException> {
        store.create(
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
                species =
                    listOf(
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 5,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 10,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                    ),
            )
        )
      }
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)

      assertThrows<PlantingSeasonClosedException> {
        store.create(
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
            ),
        )
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.create(
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
            )
        )
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateExistsException when date already exists`() {
      insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonScheduledDateExistsException> {
        store.create(
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
            )
        )
      }
    }
  }

  @Nested
  inner class Update {
    @Test
    fun `updates the scheduled date`() {
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      val newDate = LocalDate.EPOCH.plusDays(1)

      clock.instant = Instant.ofEpochSecond(100)
      val model =
          PlantingSeasonScheduledDateModel(
              plantingSeasonId = plantingSeasonId,
              date = newDate,
          )

      store.update(scheduledDateId, model)

      assertTableEquals(
          ScheduledPlantingDatesRecord(
              plantingSeasonId = plantingSeasonId,
              date = newDate,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      assertTableEmpty(SCHEDULED_PLANTING_DATE_SPECIES)
    }

    @Test
    fun `updates the species for the scheduled date`() {
      val speciesId2 = insertSpecies()
      val substratumId2 = insertSubstratum()
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies(
          quantity = 5,
          speciesId = speciesId,
          substratumId = substratumId,
      )
      insertScheduledPlantingDateSpecies(
          quantity = 10,
          speciesId = speciesId2,
          substratumId = substratumId2,
      )

      clock.instant = Instant.ofEpochSecond(100)
      val model =
          PlantingSeasonScheduledDateModel(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH.plusDays(1),
              species =
                  listOf(
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 11,
                          speciesId = speciesId2,
                          substratumId = substratumId2,
                      ),
                      PlantingSeasonScheduledDateSpecies(
                          quantity = 15,
                          speciesId = speciesId2,
                          substratumId = substratumId,
                      ),
                  ),
          )

      store.update(scheduledDateId, model)

      assertTableEquals(
          ScheduledPlantingDatesRecord(
              plantingSeasonId = plantingSeasonId,
              date = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      assertTableEquals(
          listOf(
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 11,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId2,
                  substratumId = substratumId2,
              ),
              ScheduledPlantingDateSpeciesRecord(
                  quantity = 15,
                  scheduledPlantingDateId = scheduledDateId,
                  speciesId = speciesId2,
                  substratumId = substratumId,
              ),
          )
      )
    }

    @Test
    fun `throws DuplicateKeyException when same species-substratum combination is added twice`() {
      val scheduledDate = insertPlantingSeasonScheduledDate()

      assertThrows<DuplicateKeyException> {
        store.update(
            scheduledDate,
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
                species =
                    listOf(
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 5,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 10,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                    ),
            ),
        )
      }
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)
      val scheduledDateId = insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonClosedException> {
        store.update(
            scheduledDateId,
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
                species =
                    listOf(
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 5,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 10,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                    ),
            ),
        )
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.update(
            scheduledDateId,
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH.plusDays(1),
            ),
        )
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when date doesn't exist`() {
      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.update(
            ScheduledPlantingDateId((99999L)),
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
            ),
        )
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when date belongs to different planting season`() {
      insertPlantingSeason()
      val scheduledDate = insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.update(
            scheduledDate,
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
            ),
        )
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes the scheduled date`() {
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies()

      store.delete(plantingSeasonId, scheduledDateId)

      assertTableEmpty(SCHEDULED_PLANTING_DATES)
      assertTableEmpty(SCHEDULED_PLANTING_DATE_SPECIES)
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)
      val scheduledDateId = insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonClosedException> {
        store.delete(plantingSeasonId, scheduledDateId)
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when date doesn't exist`() {
      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.delete(plantingSeasonId, ScheduledPlantingDateId(99999L))
      }
    }

    @Test
    fun `throws PlantingSeasonScheduledDateNotFoundException when date belongs to different season`() {
      insertPlantingSeason()
      val scheduledDateId = insertPlantingSeasonScheduledDate()

      assertThrows<PlantingSeasonScheduledDateNotFoundException> {
        store.delete(plantingSeasonId, scheduledDateId)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies()
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      assertThrows<AccessDeniedException> { store.delete(plantingSeasonId, scheduledDateId) }
    }
  }
}
