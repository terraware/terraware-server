package com.terraformation.backend.species

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.SpeciesInUseException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.model.NewSpeciesModel
import com.terraformation.backend.species.model.SpeciesModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SpeciesServiceTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  override val user: TerrawareUser = mockUser()

  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao)
  }
  private val speciesChecker: SpeciesChecker = mockk()
  private val service: SpeciesService by lazy {
    SpeciesService(dslContext, eventPublisher, speciesChecker, speciesStore)
  }

  @BeforeEach
  fun setUp() {
    every { speciesChecker.checkSpecies(any()) } just Runs
    every { speciesChecker.recheckSpecies(any(), any()) } just Runs
    every { user.canCreateSpecies(organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `createSpecies checks for problems with species data`() {
    val speciesId =
        service.createSpecies(
            NewSpeciesModel(
                id = null, organizationId = organizationId, scientificName = "Scientific name"))

    verify { speciesChecker.checkSpecies(speciesId) }
  }

  @Test
  fun `updateSpecies checks for problems with species data`() {
    val speciesId = SpeciesId(1)

    insertSpecies(speciesId, "Old name")
    val originalModel = speciesStore.fetchSpeciesById(speciesId)

    val updatedModel = service.updateSpecies(originalModel.copy(scientificName = "New name"))

    assertEquals("New name", updatedModel.scientificName)

    verify {
      speciesChecker.recheckSpecies(
          match { it.scientificName == "Old name" },
          match { it.scientificName == "New name" },
      )
    }

    eventPublisher.assertEventPublished(
        SpeciesEditedEvent(
            species =
                SpeciesModel(
                    averageWoodDensity = null,
                    checkedTime = null,
                    commonName = null,
                    conservationCategory = null,
                    dbhSource = null,
                    dbhValue = null,
                    deletedTime = null,
                    ecologicalRoleKnown = null,
                    familyName = null,
                    id = inserted.speciesId,
                    initialScientificName = "Old name",
                    organizationId = inserted.organizationId,
                    scientificName = "New name")))
  }

  @Test
  fun `deleteSpecies throws exception if species is in use`() {
    val speciesId = SpeciesId(1)

    insertSpecies(speciesId, "species name")
    insertFacility()
    insertBatch(speciesId = speciesId)

    assertThrows<SpeciesInUseException> { service.deleteSpecies(speciesId) }
  }

  @Test
  fun `deleteSpecies deletes species when not in use`() {
    val speciesId = SpeciesId(1)

    insertSpecies(speciesId, "species name")
    assertNotNull(speciesStore.fetchSpeciesById(speciesId))

    service.deleteSpecies(speciesId)
    assertThrows<SpeciesNotFoundException> { speciesStore.fetchSpeciesById(speciesId) }
  }
}
