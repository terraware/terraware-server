package com.terraformation.backend.species

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SpeciesServiceTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  override val user: TerrawareUser = mockUser()

  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(clock, dslContext, speciesDao, speciesProblemsDao)
  }
  private val speciesChecker: SpeciesChecker = mockk()
  private val service: SpeciesService by lazy {
    SpeciesService(dslContext, speciesChecker, speciesStore)
  }

  @BeforeEach
  fun setUp() {
    every { clock.instant() } returns Instant.EPOCH
    every { speciesChecker.checkSpecies(any()) } just Runs
    every { speciesChecker.recheckSpecies(any(), any()) } just Runs
    every { user.canCreateSpecies(organizationId) } returns true
    every { user.canReadOrganization(organizationId) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `getOrCreateSpecies returns existing species ID for organization`() {
    val speciesId = SpeciesId(1)
    val scientificName = "test"

    insertSpecies(speciesId, scientificName = scientificName, organizationId = organizationId)

    assertEquals(speciesId, service.getOrCreateSpecies(organizationId, scientificName))
  }

  @Test
  fun `getOrCreateSpecies creates new species if it does not exist in organization`() {
    val otherOrgId = OrganizationId(2)
    val otherOrgSpeciesId = SpeciesId(10)
    val scientificName = "test"

    insertOrganization(otherOrgId.value)
    insertSpecies(
        otherOrgSpeciesId.value,
        scientificName = scientificName,
        organizationId = otherOrgId.value,
    )

    val speciesId = service.getOrCreateSpecies(organizationId, scientificName)

    assertNotNull(speciesId, "Should have created species")
    assertNotEquals(otherOrgSpeciesId, speciesId, "Should not use species ID from other org")

    verify { speciesChecker.checkSpecies(speciesId) }
  }

  @Test
  fun `createSpecies checks for problems with species data`() {
    val speciesId =
        service.createSpecies(
            SpeciesRow(organizationId = organizationId, scientificName = "Scientific name"))

    verify { speciesChecker.checkSpecies(speciesId) }
  }

  @Test
  fun `updateSpecies checks for problems with species data`() {
    val speciesId = SpeciesId(1)

    insertSpecies(speciesId, "Old name", organizationId = organizationId)
    val originalRow = speciesDao.fetchOneById(speciesId)!!

    val updatedRow = service.updateSpecies(originalRow.copy(scientificName = "New name"))

    assertEquals("New name", updatedRow.scientificName)

    verify {
      speciesChecker.recheckSpecies(
          match { it.scientificName == "Old name" },
          match { it.scientificName == "New name" },
      )
    }
  }
}
