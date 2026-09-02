package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.records.SpeciesProblemsRecord
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class SpeciesCheckerTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val gbifStore: GbifStore by lazy { GbifStore(dslContext) }
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        TestEventPublisher(),
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao,
    )
  }
  private val checker: SpeciesChecker by lazy {
    SpeciesChecker(gbifStore, speciesStore)
  }

  private lateinit var organizationId: OrganizationId

  private fun nonexistentProblem(speciesId: SpeciesId = inserted.speciesId) =
      SpeciesProblemsRecord(
          createdTime = clock.instant,
          fieldId = SpeciesProblemField.ScientificName,
          speciesId = speciesId,
          typeId = SpeciesProblemType.NameNotFound,
      )

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Admin)
  }

  @Nested
  inner class CheckAllUncheckedSpecies {
    @Test
    fun `checks all unchecked species`() {
      insertGbifTaxon(scientificName = "Correct name")
      val bogusId = insertSpecies(scientificName = "Bogus name")
      insertSpecies(scientificName = "Correct name")
      insertSpecies(scientificName = "Checked already", checkedTime = Instant.ofEpochSecond(30))

      val expectedSpecies =
          dslContext.fetch(SPECIES).onEach { it.checkedTime = it.checkedTime ?: clock.instant }

      checker.checkAllUncheckedSpecies(organizationId)

      assertTableEquals(expectedSpecies)
      assertTableEquals(nonexistentProblem(bogusId))
    }
  }

  @Nested
  inner class CheckSpecies {
    @Test
    fun `checks scientific name if species has not been checked`() {
      val speciesId = insertSpecies(scientificName = "Bogus name")

      checker.checkSpecies(speciesId)

      assertEquals(
          listOf(Instant.EPOCH),
          dslContext.fetchValues(SPECIES.CHECKED_TIME),
          "Checked time",
      )
      assertTableEquals(nonexistentProblem())
    }

    @Test
    fun `does nothing if species has already been checked`() {
      val speciesId = insertSpecies("Bogus name", checkedTime = Instant.EPOCH)

      val expectedSpecies = dslContext.fetch(SPECIES)

      checker.checkSpecies(speciesId)

      assertTableEquals(expectedSpecies)
      assertTableEmpty(SPECIES_PROBLEMS)
    }

    @Test
    fun `does not suggest rename that would collide with existing species`() {
      insertGbifTaxon(scientificName = "Correct species")
      insertSpecies(scientificName = "Correct species")
      val speciesId = insertSpecies("Correc species")

      checker.checkSpecies(speciesId)

      assertTableEmpty(SPECIES_PROBLEMS)
    }
  }

  @Nested
  inner class RecheckSpecies {
    @Test
    fun `checks scientific name again if it changed`() {
      val speciesId = insertSpecies("Bogus name", checkedTime = Instant.EPOCH)
      val after = speciesStore.fetchSpeciesById(speciesId)
      val before = after.copy(scientificName = "Old name")

      clock.instant = Instant.ofEpochSecond(30)
      checker.recheckSpecies(before, after)

      assertEquals(
          listOf(clock.instant),
          dslContext.fetchValues(SPECIES.CHECKED_TIME),
          "Checked time",
      )
      assertTableEquals(nonexistentProblem())
    }

    @Test
    fun `does not check scientific name again if it did not change`() {
      val speciesId = insertSpecies("Bogus name", checkedTime = Instant.EPOCH)
      val expectedSpecies = dslContext.fetch(SPECIES)
      val model = speciesStore.fetchSpeciesById(speciesId)

      checker.recheckSpecies(model, model.copy(familyName = "New family"))

      assertTableEquals(expectedSpecies)
      assertTableEmpty(SPECIES_PROBLEMS)
    }
  }
}
