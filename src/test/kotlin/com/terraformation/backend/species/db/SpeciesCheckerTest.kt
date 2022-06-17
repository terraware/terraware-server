package com.terraformation.backend.species.db

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesProblemField
import com.terraformation.backend.db.SpeciesProblemType
import com.terraformation.backend.db.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SpeciesCheckerTest {
  private val gbifStore: GbifStore = mockk()
  private val speciesStore: SpeciesStore = mockk()
  private val checker = SpeciesChecker(gbifStore, speciesStore)

  private val organizationId = OrganizationId(1)
  private val speciesId = SpeciesId(1)
  private val nonexistentProblem =
      SpeciesProblemsRow(
          speciesId = speciesId,
          fieldId = SpeciesProblemField.ScientificName,
          typeId = SpeciesProblemType.NameNotFound)

  @BeforeEach
  fun setUp() {
    every { gbifStore.checkScientificName(any()) } returns null
    every { gbifStore.checkScientificName("Bogus name") } returns nonexistentProblem
    every { speciesStore.updateProblems(any(), any()) } just Runs
  }

  @Test
  fun `checkAllUncheckedSpecies checks all unchecked species`() {
    val bogusId = speciesId
    val correctId = SpeciesId(2)
    val uncheckedSpeciesIds = listOf(correctId, bogusId)

    every { speciesStore.fetchUncheckedSpeciesIds(organizationId) } returns uncheckedSpeciesIds
    every { speciesStore.fetchSpeciesById(correctId) } returns
        SpeciesRow(id = correctId, organizationId = organizationId, scientificName = "Correct name")
    every { speciesStore.fetchSpeciesById(bogusId) } returns
        SpeciesRow(id = bogusId, organizationId = organizationId, scientificName = "Bogus name")

    checker.checkAllUncheckedSpecies(organizationId)

    verify { gbifStore.checkScientificName("Correct name") }
    verify { gbifStore.checkScientificName("Bogus name") }
    verify { speciesStore.updateProblems(correctId, emptyList()) }
    verify { speciesStore.updateProblems(bogusId, listOf(nonexistentProblem)) }
  }

  @Test
  fun `checkSpecies checks scientific name if species has not been checked`() {
    every { speciesStore.fetchSpeciesById(speciesId) } returns
        SpeciesRow(id = speciesId, scientificName = "Bogus name")

    checker.checkSpecies(speciesId)

    verify { gbifStore.checkScientificName("Bogus name") }
    verify { speciesStore.updateProblems(speciesId, listOf(nonexistentProblem)) }
  }

  @Test
  fun `checkSpecies does nothing if species has already been checked`() {
    every { speciesStore.fetchSpeciesById(speciesId) } returns
        SpeciesRow(id = speciesId, scientificName = "Bogus name", checkedTime = Instant.EPOCH)

    checker.checkSpecies(speciesId)

    verify(exactly = 0) { gbifStore.checkScientificName(any()) }
    verify(exactly = 0) { speciesStore.updateProblems(any(), any()) }
  }

  @Test
  fun `recheckSpecies checks scientific name again if it changed`() {
    checker.recheckSpecies(
        SpeciesRow(id = speciesId, scientificName = "Correct name"),
        SpeciesRow(id = speciesId, scientificName = "Bogus name"))

    verify { gbifStore.checkScientificName("Bogus name") }
    verify { speciesStore.updateProblems(speciesId, listOf(nonexistentProblem)) }
  }

  @Test
  fun `recheckSpecies does not check scientific name again if it did not change`() {
    val speciesRow =
        SpeciesRow(id = speciesId, scientificName = "Bogus name", familyName = "Old family")

    checker.recheckSpecies(speciesRow, speciesRow.copy(familyName = "New family"))

    verify(exactly = 0) { gbifStore.checkScientificName(any()) }
    verify(exactly = 0) { speciesStore.updateProblems(any(), any()) }
  }
}
