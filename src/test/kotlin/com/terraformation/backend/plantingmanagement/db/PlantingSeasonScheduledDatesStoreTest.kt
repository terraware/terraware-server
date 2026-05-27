package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.ScheduledPlantingDateId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.ScheduledPlantingDateSpeciesRecord
import com.terraformation.backend.db.tracking.tables.records.ScheduledPlantingDatesRecord
import com.terraformation.backend.db.tracking.tables.references.SCHEDULED_PLANTING_DATE_SPECIES
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonScheduledDateSpecies
import java.time.Instant
import java.time.LocalDate
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
    fun `throws IllegalArgumentException when a quantity is less than 0`() {
      val speciesId2 = insertSpecies()
      val scheduledDateId = insertPlantingSeasonScheduledDate()
      insertScheduledPlantingDateSpecies(speciesId = speciesId)
      insertScheduledPlantingDateSpecies(speciesId = speciesId2)

      assertThrows<IllegalArgumentException> {
        store.update(
            scheduledDateId,
            PlantingSeasonScheduledDateModel(
                plantingSeasonId = plantingSeasonId,
                date = LocalDate.EPOCH,
                species =
                    listOf(
                        PlantingSeasonScheduledDateSpecies(
                            quantity = -1,
                            speciesId = speciesId,
                            substratumId = substratumId,
                        ),
                        PlantingSeasonScheduledDateSpecies(
                            quantity = 1,
                            speciesId = speciesId2,
                            substratumId = substratumId,
                        ),
                    ),
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
  }
}
