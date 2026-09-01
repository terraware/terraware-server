package com.terraformation.backend.species

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.OrganizationLocationUpdatedEvent
import com.terraformation.backend.customer.event.ProjectUpdatedEvent
import com.terraformation.backend.customer.event.ProjectUpdatedEventValues
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.SpeciesInUseException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.ExternalDatasetType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesNativity
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.default_schema.tables.records.ProjectSpeciesRecord
import com.terraformation.backend.db.default_schema.tables.references.PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.tables.references.SPECIES_PROBLEMS
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.db.ExternalDatasetStore
import com.terraformation.backend.species.db.GbifStore
import com.terraformation.backend.species.db.ProjectSpeciesStore
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesNativityCalculator
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.event.SpeciesImportedEvent
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.NewSpeciesModel
import com.terraformation.backend.species.model.SpeciesDataSourceModel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SpeciesServiceTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  override val user: TerrawareUser = mockUser()

  private val externalDatasetStore: ExternalDatasetStore by lazy {
    ExternalDatasetStore(dslContext)
  }
  private val gbifStore: GbifStore by lazy { GbifStore(dslContext) }
  private val projectSpeciesStore: ProjectSpeciesStore by lazy {
    ProjectSpeciesStore(
        clock,
        dslContext,
        ParentStore(dslContext),
        SpeciesNativityCalculator(dslContext),
    )
  }
  private val speciesStore: SpeciesStore by lazy {
    SpeciesStore(
        clock,
        dslContext,
        eventPublisher,
        speciesDao,
        speciesEcosystemTypesDao,
        speciesGrowthFormsDao,
        speciesProblemsDao,
    )
  }
  private val speciesChecker: SpeciesChecker = mockk()
  private val service: SpeciesService by lazy {
    SpeciesService(
        dslContext,
        eventPublisher,
        externalDatasetStore,
        gbifStore,
        projectSpeciesStore,
        speciesChecker,
        speciesStore,
    )
  }

  private val gbifImportedDate = LocalDate.of(2026, 2, 2)
  private val griisDate = LocalDate.of(2026, 1, 2)

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()

    every { speciesChecker.checkSpecies(any()) } just Runs
    every { speciesChecker.recheckSpecies(any(), any()) } just Runs
    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true

    insertExternalDatasetImport(
        type = ExternalDatasetType.GBIF,
        importedTime =
            ZonedDateTime.of(gbifImportedDate, LocalTime.of(5, 6, 7, 8), ZoneOffset.UTC)
                .toInstant(),
        lastPublicationDate = null,
    )
  }

  @Nested
  inner class CreateSpecies {
    @Test
    fun `checks for problems with species data`() {
      val speciesId =
          service.createSpecies(
              NewSpeciesModel(organizationId = organizationId, scientificName = "Scientific name")
          )

      verify { speciesChecker.checkSpecies(speciesId) }
    }

    @Test
    fun `attributes common and family names to current dataset if they match`() {
      insertGbifTaxon(
          scientificName = "Scientific name",
          commonNames = listOf("Common en" to "en"),
          familyName = "Family",
      )

      val speciesId =
          service.createSpecies(
              NewSpeciesModel(
                  commonName = "Common en",
                  familyName = "Family",
                  organizationId = organizationId,
                  scientificName = "Scientific name",
              )
          )

      val speciesModel = speciesStore.fetchSpeciesById(speciesId)

      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.commonNameSource,
          "Common name source",
      )
      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.familyNameSource,
          "Family name source",
      )
    }

    @Test
    fun `sets pending nativity based on project location`() {
      every { user.canReadProject(any()) } returns true

      val botanicalCountryCode = insertBotanicalCountry()
      val projectId = insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "AR")
      insertGriisInvasiveListing()

      val speciesId =
          service.createSpecies(
              NewSpeciesModel(
                  organizationId = organizationId,
                  projectIds = setOf(projectId),
                  scientificName = "Scientific name",
              )
          )

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              pendingNativityDatasetDate = griisDate,
              pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              pendingNativityId = SpeciesNativity.Invasive,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `sets pending org-level nativity if org has location and fewer than two projects`() {
      val botanicalCountryCode = insertBotanicalCountry()
      val locatedOrganizationId =
          insertOrganization(botanicalCountryCode = botanicalCountryCode, countryCode = "AR")
      insertProject()

      insertGriisInvasiveListing()

      val speciesId =
          service.createSpecies(
              NewSpeciesModel(
                  organizationId = locatedOrganizationId,
                  scientificName = "Scientific name",
              )
          )

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = locatedOrganizationId,
              pendingNativityDatasetDate = griisDate,
              pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              pendingNativityId = SpeciesNativity.Invasive,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `removes suggested rename of existing species that would collide with new one`() {
      insertSpecies(scientificName = "Other species")
      insertSpeciesProblem()
      val expectedProblems = dslContext.fetch(SPECIES_PROBLEMS)

      insertSpecies(scientificName = "Correc name")
      insertSpeciesProblem(
          type = SpeciesProblemType.NameMisspelled,
          suggestedValue = "Correct name",
      )

      service.createSpecies(
          NewSpeciesModel(organizationId = organizationId, scientificName = "Correct name")
      )

      assertTableEquals(expectedProblems)
    }
  }

  @Nested
  inner class UpdateSpecies {
    @Test
    fun `checks for problems with species data`() {
      val speciesId = insertSpecies("Old name")
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
                  ExistingSpeciesModel(
                      averageWoodDensity = null,
                      checkedTime = null,
                      commonName = null,
                      conservationCategory = null,
                      createdTime = originalModel.createdTime,
                      dbhSource = null,
                      dbhValue = null,
                      deletedTime = null,
                      ecologicalRoleKnown = null,
                      familyName = null,
                      id = inserted.speciesId,
                      initialScientificName = "Old name",
                      modifiedTime = clock.instant(),
                      organizationId = inserted.organizationId,
                      scientificName = "New name",
                  )
          )
      )
    }

    @Test
    fun `attributes common and family names to GBIF if they match`() {
      val speciesId = insertSpecies(scientificName = "Scientific name")
      insertGbifTaxon(
          scientificName = "Scientific name",
          commonNames = listOf("Common" to "en"),
          familyName = "Family",
      )

      val originalModel = speciesStore.fetchSpeciesById(speciesId)
      val updatedModel =
          originalModel.copy(
              commonName = "Common",
              familyName = "Family",
          )

      service.updateSpecies(updatedModel)

      val speciesModel = speciesStore.fetchSpeciesById(speciesId)

      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.commonNameSource,
          "Common name source",
      )
      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.familyNameSource,
          "Family name source",
      )
    }

    @Test
    fun `does not modify existing name sources or nativity if names have not changed`() {
      val speciesId =
          insertSpecies(
              scientificName = "Scientific name",
              commonName = "My common name",
              commonNameDatasetDate = LocalDate.of(2026, 1, 1),
              commonNameDatasetType = ExternalDatasetType.GBIF,
              familyName = "MyFamily",
              familyNameDatasetDate = LocalDate.of(2026, 1, 2),
              familyNameDatasetType = ExternalDatasetType.GBIF,
          )
      insertGbifTaxon(
          scientificName = "Scientific name",
          commonNames = listOf("Common" to "en"),
          familyName = "Family",
      )

      insertProject()
      insertProjectSpecies(speciesId = speciesId, calculatedNativity = SpeciesNativity.Invasive)

      val projectSpeciesBefore = dslContext.fetch(PROJECT_SPECIES)

      val originalModel = speciesStore.fetchSpeciesById(speciesId)
      val updatedModel = originalModel.copy(localUsesKnown = "Edited value")

      service.updateSpecies(updatedModel)

      val speciesModel = speciesStore.fetchSpeciesById(speciesId)

      assertEquals(
          SpeciesDataSourceModel(LocalDate.of(2026, 1, 1), ExternalDatasetType.GBIF),
          speciesModel.commonNameSource,
          "Common name source",
      )
      assertEquals(
          SpeciesDataSourceModel(LocalDate.of(2026, 1, 2), ExternalDatasetType.GBIF),
          speciesModel.familyNameSource,
          "Family name source",
      )
      assertTableEquals(projectSpeciesBefore)
    }

    @Test
    fun `recalculates name sources and nativity if scientific name has changed`() {
      // The nativity recalculation logic is tested in ProjectSpeciesStoreTest; this is just to make
      // sure a name change triggers a recalculation at all.
      val speciesId =
          insertSpecies(
              scientificName = "Old name",
              commonName = "Common name",
              commonNameDatasetDate = LocalDate.of(2026, 1, 1),
              commonNameDatasetType = ExternalDatasetType.GBIF,
              familyNameDatasetDate = LocalDate.of(2026, 1, 2),
              familyNameDatasetType = ExternalDatasetType.GBIF,
              familyName = "Family",
          )
      insertGbifTaxon(
          scientificName = "New name",
          commonNames = listOf("Common name" to "en"),
          familyName = "Family",
      )

      val botanicalCountryCode = insertBotanicalCountry()
      insertGriisInvasiveListing(scientificName = "New name")

      val projectId = insertProject(botanicalCountryCode = botanicalCountryCode, countryCode = "AR")
      insertProjectSpecies(
          projectId = projectId,
          speciesId = speciesId,
          calculatedNativity = SpeciesNativity.Unknown,
      )

      val originalModel = speciesStore.fetchSpeciesById(speciesId)
      val updatedModel = originalModel.copy(scientificName = "New name")

      service.updateSpecies(updatedModel)

      val speciesModel = speciesStore.fetchSpeciesById(speciesId)

      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.commonNameSource,
          "Common name source",
      )
      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          speciesModel.familyNameSource,
          "Family name source",
      )
      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              pendingNativityDatasetDate = griisDate,
              pendingNativityDatasetTypeId = ExternalDatasetType.GRIIS,
              pendingNativityId = SpeciesNativity.Invasive,
              projectId = projectId,
              speciesId = speciesId,
          )
      )
    }

    @Test
    fun `removes suggestions that would collide with updated species name`() {
      insertSpecies(scientificName = "Other name")
      insertSpeciesProblem()
      val expectedProblems = dslContext.fetch(SPECIES_PROBLEMS)

      insertSpecies(scientificName = "Correc name")
      insertSpeciesProblem(
          type = SpeciesProblemType.NameMisspelled,
          suggestedValue = "Correct name",
      )
      val speciesId = insertSpecies(scientificName = "Original name")

      val initial = speciesStore.fetchSpeciesById(speciesId)
      service.updateSpecies(initial.copy(scientificName = "Correct name"))

      assertTableEquals(expectedProblems)
    }
  }

  @Nested
  inner class DeleteSpecies {
    @Test
    fun `throws exception if species is in use in accession`() {
      val speciesId = insertSpecies("species name")
      insertFacility()
      insertAccession(speciesId = speciesId)

      assertThrows<SpeciesInUseException> { service.deleteSpecies(speciesId) }
    }

    @Test
    fun `throws exception if species is in use in batch`() {
      val speciesId = insertSpecies("species name")
      insertFacility()
      insertBatch(speciesId = speciesId)

      assertThrows<SpeciesInUseException> { service.deleteSpecies(speciesId) }
    }

    @Test
    fun `throws exception if species is in use in observation`() {
      val speciesId = insertSpecies("species name")
      insertPlantingSite()
      insertStratum()
      insertSubstratum()
      insertMonitoringPlot()
      insertObservation()
      insertObservationPlot()
      insertRecordedPlant(speciesId = speciesId)

      assertThrows<SpeciesInUseException> { service.deleteSpecies(speciesId) }
    }

    @Test
    fun `deletes species when not in use`() {
      val speciesId = insertSpecies("species name")
      insertProject()
      insertProjectSpecies()
      assertNotNull(speciesStore.fetchSpeciesById(speciesId))

      service.deleteSpecies(speciesId)

      assertThrows<SpeciesNotFoundException> { speciesStore.fetchSpeciesById(speciesId) }

      assertTableEmpty(PROJECT_SPECIES)
    }
  }

  @Nested
  inner class AcceptProblemSuggestion {
    @Test
    fun `updates species if name is synonym`() {
      insertGbifTaxon("Correct name", listOf("Common" to "en"), "Family")
      val speciesId =
          service.createSpecies(
              NewSpeciesModel(
                  commonName = "Common",
                  familyName = "Family",
                  organizationId = organizationId,
                  scientificName = "Incorrect name",
              )
          )

      val problemsRow =
          SpeciesProblemsRow(
              createdTime = Instant.EPOCH,
              fieldId = SpeciesProblemField.ScientificName,
              speciesId = speciesId,
              suggestedValue = "Correct name",
              typeId = SpeciesProblemType.NameIsSynonym,
          )
      speciesProblemsDao.insert(problemsRow)

      val updated = service.acceptProblemSuggestion(problemsRow.id!!)

      assertEquals("Correct name", updated.scientificName, "Scientific name")
      assertEquals("Incorrect name", updated.initialScientificName, "Initial scientific name")
      assertEquals(
          SpeciesDataSourceModel(gbifImportedDate, ExternalDatasetType.GBIF),
          updated.commonNameSource,
          "Common name source",
      )
      assertEquals(updated.commonNameSource, updated.familyNameSource, "Family name source")
    }

    @Test
    fun `throws exception if suggested scientific name is already in use`() {
      service.createSpecies(
          NewSpeciesModel(organizationId = organizationId, scientificName = "Correct name")
      )
      val speciesIdWithOutdatedName =
          service.createSpecies(
              NewSpeciesModel(organizationId = organizationId, scientificName = "Outdated name")
          )
      val problemsRow =
          SpeciesProblemsRow(
              createdTime = Instant.EPOCH,
              fieldId = SpeciesProblemField.ScientificName,
              speciesId = speciesIdWithOutdatedName,
              suggestedValue = "Correct name",
              typeId = SpeciesProblemType.NameIsSynonym,
          )
      speciesProblemsDao.insert(problemsRow)

      assertThrows<ScientificNameExistsException> {
        service.acceptProblemSuggestion(problemsRow.id!!)
      }
    }
  }

  @Nested
  inner class OnProjectUpdatedEvent {
    @Test
    fun `recalculates species nativity on location change`() {
      // The recalculation logic is tested in ProjectSpeciesStoreTest; this is just to make sure the
      // event triggers a recalculation at all.
      insertProject()
      insertSpecies()
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Invasive)

      service.on(
          ProjectUpdatedEvent(
              changedFrom = ProjectUpdatedEventValues(countryCode = "US"),
              changedTo = ProjectUpdatedEventValues(countryCode = null),
              organizationId = organizationId,
              projectId = inserted.projectId,
          )
      )

      assertTableEquals(
          ProjectSpeciesRecord(organizationId, inserted.projectId, inserted.speciesId)
      )

      assertIsEventListener<ProjectUpdatedEvent>(service)
    }

    @Test
    fun `does not update species if location was unchanged`() {
      insertProject()
      insertSpecies()
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Invasive)

      val before = dslContext.fetch(PROJECT_SPECIES)

      service.on(
          ProjectUpdatedEvent(
              changedFrom = ProjectUpdatedEventValues(description = "X"),
              changedTo = ProjectUpdatedEventValues(description = "Y"),
              organizationId = organizationId,
              projectId = inserted.projectId,
          )
      )

      assertTableEquals(before)
    }
  }

  @Nested
  inner class OnOrganizationLocationUpdatedEvent {
    @Test
    fun `recalculates species nativity on location change for single-project org`() {
      // The recalculation logic is tested in ProjectSpeciesStoreTest; this is just to make sure the
      // event triggers a recalculation at all.
      insertProject()
      insertSpecies()
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Invasive)

      service.on(OrganizationLocationUpdatedEvent(null, null, organizationId))

      assertTableEquals(
          ProjectSpeciesRecord(organizationId, inserted.projectId, inserted.speciesId)
      )

      assertIsEventListener<OrganizationLocationUpdatedEvent>(service)
    }

    @Test
    fun `does not update project species in multi-project org`() {
      insertProject()
      insertSpecies()
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Invasive)
      insertProject()
      insertProjectSpecies(calculatedNativity = SpeciesNativity.Introduced)

      val before = dslContext.fetch(PROJECT_SPECIES)

      service.on(OrganizationLocationUpdatedEvent(null, null, organizationId))

      assertTableEquals(before)
    }
  }

  @Nested
  inner class OnSpeciesImportedEvent {
    @Test
    fun `resets nativities if species is newly imported`() {
      insertBotanicalCountry(level3Code = "KEN")
      organizationId =
          insertOrganization(botanicalCountryCode = "KEN", countryCode = "KE", name = "Kenya Org")
      val speciesId = insertSpecies()

      service.on(SpeciesImportedEvent(alreadyExisted = false, speciesId = speciesId))

      assertTableEquals(
          ProjectSpeciesRecord(
              organizationId = organizationId,
              pendingNativityId = SpeciesNativity.Unknown,
              speciesId = speciesId,
          )
      )

      assertIsEventListener<SpeciesImportedEvent>(service)
    }

    @Test
    fun `does not reset nativities on re-import of existing species`() {
      insertBotanicalCountry(level3Code = "KEN")
      insertOrganization(botanicalCountryCode = "KEN", countryCode = "KE", name = "Kenya Org")
      val speciesId = insertSpecies()

      service.on(SpeciesImportedEvent(alreadyExisted = true, speciesId = speciesId))

      assertTableEmpty(PROJECT_SPECIES)
    }
  }

  /** Publishes a GRIIS listing of a species as invasive in Argentina. */
  private fun insertGriisInvasiveListing(scientificName: String = "Scientific name") {
    insertExternalDatasetImport(type = ExternalDatasetType.GRIIS, lastPublicationDate = griisDate)
    insertGriisResource(countryCode = "AR")
    insertGriisTaxon(scientificName = scientificName, isInvasive = true)
  }
}
