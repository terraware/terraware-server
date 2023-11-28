package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.ConservationCategory
import com.terraformation.backend.db.default_schema.EcosystemType
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GrowthForm
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedStorageBehavior
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemField
import com.terraformation.backend.db.default_schema.SpeciesProblemType
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesEcosystemTypesRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.mockUser
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.NewSpeciesModel
import com.terraformation.backend.tracking.db.PlantingSubzoneNotFoundException
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class SpeciesStoreTest : DatabaseTest(), RunsAsUser {
  private val clock = TestClock()
  override val user: TerrawareUser = mockUser()

  private lateinit var store: SpeciesStore

  @BeforeEach
  fun setUp() {
    store =
        SpeciesStore(clock, dslContext, speciesDao, speciesEcosystemTypesDao, speciesProblemsDao)

    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `createSpecies inserts species`() {
    val model =
        NewSpeciesModel(
            commonName = "common",
            conservationCategory = ConservationCategory.Endangered,
            deletedTime = Instant.EPOCH,
            familyName = "family",
            growthForm = GrowthForm.Shrub,
            id = null,
            organizationId = organizationId,
            rare = false,
            scientificName = "test",
            seedStorageBehavior = SeedStorageBehavior.Recalcitrant,
        )

    val speciesId = store.createSpecies(model)
    assertNotNull(speciesId, "Should have returned ID")

    val expected =
        listOf(
            SpeciesRow(
                commonName = "common",
                conservationCategoryId = ConservationCategory.Endangered,
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                deletedBy = null,
                deletedTime = null,
                familyName = "family",
                growthFormId = GrowthForm.Shrub,
                id = speciesId,
                initialScientificName = "test",
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                organizationId = organizationId,
                rare = false,
                scientificName = "test",
                seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
            ))

    val actual = speciesDao.findAll()
    assertEquals(expected, actual)
  }

  @Test
  fun `createSpecies allows the same name to be used in different organizations`() {
    val otherOrgId = OrganizationId(2)
    insertOrganization(otherOrgId.value)

    val model = NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "test")
    store.createSpecies(model)

    assertDoesNotThrow { store.createSpecies(model.copy(organizationId = otherOrgId)) }
  }

  @Test
  fun `createSpecies reuses previously deleted species if one exists`() {
    val originalSpeciesId =
        store.createSpecies(
            NewSpeciesModel(
                commonName = "original common",
                conservationCategory = ConservationCategory.LeastConcern,
                ecosystemTypes = setOf(EcosystemType.Mangroves),
                familyName = "original family",
                id = null,
                organizationId = organizationId,
                scientificName = "test",
                growthForm = GrowthForm.Fern,
                rare = false,
                seedStorageBehavior = SeedStorageBehavior.Orthodox,
            ))
    val originalRow = speciesDao.fetchOneById(originalSpeciesId)!!

    store.deleteSpecies(originalSpeciesId)

    val editedModel =
        NewSpeciesModel(
            commonName = "edited common",
            conservationCategory = ConservationCategory.NearThreatened,
            ecosystemTypes = setOf(EcosystemType.Tundra),
            familyName = "edited family",
            id = null,
            organizationId = organizationId,
            growthForm = GrowthForm.Shrub,
            rare = true,
            scientificName = "test",
            seedStorageBehavior = SeedStorageBehavior.Recalcitrant,
        )

    val newInstant = Instant.ofEpochSecond(500)
    clock.instant = newInstant

    val reusedSpeciesId = store.createSpecies(editedModel)

    val expectedSpecies =
        SpeciesRow(
            commonName = "edited common",
            conservationCategoryId = ConservationCategory.NearThreatened,
            createdBy = originalRow.createdBy,
            createdTime = originalRow.createdTime,
            familyName = "edited family",
            id = originalSpeciesId,
            initialScientificName = "test",
            growthFormId = GrowthForm.Shrub,
            organizationId = organizationId,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            rare = true,
            scientificName = "test",
            seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
        )

    val actualSpecies = speciesDao.fetchOneById(reusedSpeciesId)
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes =
        listOf(SpeciesEcosystemTypesRow(originalSpeciesId, EcosystemType.Tundra))

    val actualEcosystemTypes = speciesEcosystemTypesDao.fetchBySpeciesId(originalSpeciesId)
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)
  }

  @Test
  fun `createSpecies throws exception if name already exists for organization`() {
    val model = NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "test")
    store.createSpecies(model)

    assertThrows<DuplicateKeyException> { store.createSpecies(model) }
  }

  @Test
  fun `createSpecies throws exception if user has no permission to create species`() {
    every { user.canCreateSpecies(organizationId) } returns false
    assertThrows<AccessDeniedException> {
      store.createSpecies(
          NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "dummy"))
    }
  }

  @Test
  fun `updateSpecies updates all modifiable fields`() {
    val initial =
        NewSpeciesModel(
            commonName = "original common",
            conservationCategory = ConservationCategory.Extinct,
            ecosystemTypes = setOf(EcosystemType.Mangroves, EcosystemType.Tundra),
            familyName = "original family",
            growthForm = GrowthForm.Shrub,
            id = null,
            organizationId = organizationId,
            rare = true,
            scientificName = "original scientific",
            seedStorageBehavior = SeedStorageBehavior.Unknown,
        )
    val speciesId = store.createSpecies(initial)

    val bogusOrganizationId = OrganizationId(10000)
    val bogusInstant = Instant.ofEpochSecond(1000)

    val newInstant = Instant.ofEpochSecond(500)
    clock.instant = newInstant

    val update =
        ExistingSpeciesModel(
            commonName = "new common",
            conservationCategory = ConservationCategory.ExtinctInTheWild,
            deletedTime = bogusInstant,
            ecosystemTypes = setOf(EcosystemType.BorealForestsTaiga, EcosystemType.Tundra),
            familyName = "new family",
            growthForm = GrowthForm.Fern,
            id = speciesId,
            initialScientificName = "new initial",
            organizationId = bogusOrganizationId,
            rare = false,
            scientificName = "new scientific",
            seedStorageBehavior = SeedStorageBehavior.Orthodox,
        )

    val expectedSpecies =
        SpeciesRow(
            commonName = "new common",
            conservationCategoryId = ConservationCategory.ExtinctInTheWild,
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            deletedBy = null,
            deletedTime = null,
            familyName = "new family",
            growthFormId = GrowthForm.Fern,
            id = speciesId,
            initialScientificName = "original scientific",
            modifiedBy = user.userId,
            modifiedTime = newInstant,
            organizationId = organizationId,
            rare = false,
            scientificName = "new scientific",
            seedStorageBehaviorId = SeedStorageBehavior.Orthodox,
        )

    store.updateSpecies(update)

    val actualSpecies = speciesDao.fetchOneById(speciesId)!!
    assertEquals(expectedSpecies, actualSpecies)

    val expectedEcosystemTypes =
        setOf(
            SpeciesEcosystemTypesRow(speciesId, EcosystemType.BorealForestsTaiga),
            SpeciesEcosystemTypesRow(speciesId, EcosystemType.Tundra),
        )

    val actualEcosystemTypes = speciesEcosystemTypesDao.fetchBySpeciesId(speciesId).toSet()
    assertEquals(expectedEcosystemTypes, actualEcosystemTypes)
  }

  @Test
  fun `updateSpecies throws exception if user has no permission to update species`() {
    every { user.canUpdateSpecies(any()) } returns false

    val speciesId =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "dummy"))

    assertThrows<AccessDeniedException> {
      store.updateSpecies(
          ExistingSpeciesModel(
              id = speciesId, organizationId = organizationId, scientificName = "other"))
    }
  }

  @Test
  fun `deleteSpecies marks species as deleted`() {
    // Make sure it only deletes the species in question, not the whole table
    store.createSpecies(
        NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "other"))
    val expected = store.findAllSpecies(organizationId)

    val speciesId =
        store.createSpecies(
            NewSpeciesModel(
                id = null, organizationId = organizationId, scientificName = "to delete"))

    store.deleteSpecies(speciesId)

    val actual = store.findAllSpecies(organizationId)
    assertEquals(expected, actual)
  }

  @Test
  fun `deleteSpecies throws exception if user has no permission to delete species`() {
    every { user.canDeleteSpecies(any()) } returns false

    val speciesId =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "dummy"))

    assertThrows<AccessDeniedException> { store.deleteSpecies(speciesId) }
  }

  @Test
  fun `deleteSpecies throws exception if species does not exist at all`() {
    assertThrows<SpeciesNotFoundException> { store.deleteSpecies(SpeciesId(1)) }
  }

  @Test
  fun `fetchSpeciesById returns species if it is not deleted`() {
    val speciesId =
        store.createSpecies(
            NewSpeciesModel(
                ecosystemTypes = setOf(EcosystemType.Mangroves, EcosystemType.Tundra),
                id = null,
                organizationId = organizationId,
                scientificName = "test"))

    val expected =
        ExistingSpeciesModel(
            ecosystemTypes = setOf(EcosystemType.Mangroves, EcosystemType.Tundra),
            id = speciesId,
            initialScientificName = "test",
            organizationId = organizationId,
            scientificName = "test",
        )

    val actual = store.fetchSpeciesById(speciesId)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchSpeciesById throws exception if species is deleted`() {
    val speciesId =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "test"))
    store.deleteSpecies(speciesId)

    assertThrows<SpeciesNotFoundException> { store.fetchSpeciesById(speciesId) }
  }

  @Test
  fun `fetchAllUncheckedSpeciesIds only returns unchecked species`() {
    val checkedSpeciesId = SpeciesId(1)
    val uncheckedSpeciesId = SpeciesId(2)

    insertSpecies(checkedSpeciesId, checkedTime = Instant.EPOCH)
    insertSpecies(uncheckedSpeciesId)

    assertEquals(listOf(uncheckedSpeciesId), store.fetchUncheckedSpeciesIds(organizationId))
  }

  @Nested
  inner class FetchSpeciesByPlantingSubzoneId {
    @Test
    fun `returns species for requested subzone`() {
      val speciesId1 = insertSpecies(scientificName = "Species 1")
      val speciesId2 = insertSpecies(scientificName = "Species 2", commonName = "Common 2")
      val speciesId3 = insertSpecies(scientificName = "Species 3")
      val speciesId4 = insertSpecies(scientificName = "Species 4", deletedTime = Instant.EPOCH)
      insertSpecies(scientificName = "Species 5")

      insertPlantingSite()
      insertPlantingZone()

      val subzoneId = insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting(speciesId = speciesId1)
      insertPlanting(speciesId = speciesId2)
      insertWithdrawal()
      insertDelivery()
      insertPlanting(speciesId = speciesId1)
      insertPlanting(speciesId = speciesId4)

      insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting(speciesId = speciesId2)
      insertPlanting(speciesId = speciesId3)

      every { user.canReadPlantingSubzone(subzoneId) } returns true

      val expected =
          listOf(
              ExistingSpeciesModel(
                  id = speciesId1,
                  initialScientificName = "Species 1",
                  organizationId = organizationId,
                  scientificName = "Species 1",
              ),
              ExistingSpeciesModel(
                  commonName = "Common 2",
                  id = speciesId2,
                  initialScientificName = "Species 2",
                  organizationId = organizationId,
                  scientificName = "Species 2",
              ),
          )

      val actual = store.fetchSpeciesByPlantingSubzoneId(subzoneId)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read subzone`() {
      insertPlantingSite()
      insertPlantingZone()
      val subzoneId = insertPlantingSubzone()

      every { user.canReadPlantingSubzone(subzoneId) } returns false

      assertThrows<PlantingSubzoneNotFoundException> {
        store.fetchSpeciesByPlantingSubzoneId(subzoneId)
      }
    }
  }

  @Test
  fun `fetchAllUncheckedSpeciesIds does not return deleted species`() {
    val speciesId =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "dummy"))
    store.deleteSpecies(speciesId)

    assertEquals(emptyList<SpeciesId>(), store.fetchUncheckedSpeciesIds(organizationId))
  }

  @Test
  fun `findAllProblems does not return problems with deleted species`() {
    val speciesId =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "dummy"))
    speciesProblemsDao.insert(
        SpeciesProblemsRow(
            createdTime = Instant.EPOCH,
            fieldId = SpeciesProblemField.ScientificName,
            speciesId = speciesId,
            typeId = SpeciesProblemType.NameNotFound,
        ))
    store.deleteSpecies(speciesId)

    assertEquals(
        emptyMap<SpeciesId, List<SpeciesProblemsRow>>(), store.findAllProblems(organizationId))
  }

  @Test
  fun `species reading methods throw exception if user has no permission to read organization`() {
    val scientificName = "species"
    val speciesId =
        store.createSpecies(
            NewSpeciesModel(
                id = null, organizationId = organizationId, scientificName = scientificName))

    every { user.canReadOrganization(organizationId) } returns false
    every { user.canReadSpecies(speciesId) } returns false

    assertThrows<OrganizationNotFoundException> { store.countSpecies(organizationId) }

    assertThrows<SpeciesNotFoundException> { store.fetchSpeciesById(speciesId) }

    assertThrows<OrganizationNotFoundException> { store.findAllSpecies(organizationId) }
  }

  @Test
  fun `acceptProblemSuggestion throws exception if suggested scientific name is already in use`() {
    store.createSpecies(
        NewSpeciesModel(
            id = null, organizationId = organizationId, scientificName = "Correct name"))
    val speciesIdWithOutdatedName =
        store.createSpecies(
            NewSpeciesModel(
                id = null, organizationId = organizationId, scientificName = "Outdated name"))
    val problemsRow =
        SpeciesProblemsRow(
            createdTime = Instant.EPOCH,
            fieldId = SpeciesProblemField.ScientificName,
            speciesId = speciesIdWithOutdatedName,
            suggestedValue = "Correct name",
            typeId = SpeciesProblemType.NameIsSynonym)
    speciesProblemsDao.insert(problemsRow)

    assertThrows<ScientificNameExistsException> { store.acceptProblemSuggestion(problemsRow.id!!) }
  }

  @Test
  fun `find all species in use returns no species when none in use`() {
    store.createSpecies(
        NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "other"))

    val actual = store.findAllSpecies(organizationId, true)

    assertEquals(emptyList<ExistingSpeciesModel>(), actual)
  }

  @Test
  fun `find all species in use returns species used in batches`() {
    val created =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "batch"))
    store.createSpecies(
        NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "unused"))

    // create a batch with 'other' species
    insertFacility(id = FacilityId(200))
    insertBatch(speciesId = created)

    // create another org batch
    val otherOrgId = insertOrganization(id = OrganizationId(2))
    val other =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = otherOrgId, scientificName = "other"))
    insertFacility(id = FacilityId(300))
    insertBatch(speciesId = other)

    val expected = listOf(store.fetchSpeciesById(created))
    val actual = store.findAllSpecies(organizationId, true)

    assertEquals(expected, actual)
  }

  @Test
  fun `find all species in use returns species used in accessions`() {
    val created =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "batch"))
    store.createSpecies(
        NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "unused"))

    // create an accession with 'other' species
    insertFacility(id = FacilityId(200))
    insertAccession(speciesId = created)

    // create another org accession
    val otherOrgId = insertOrganization(id = OrganizationId(2))
    val other =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = otherOrgId, scientificName = "other"))
    insertFacility(id = FacilityId(300))
    insertAccession(speciesId = other)

    val expected = listOf(store.fetchSpeciesById(created))
    val actual = store.findAllSpecies(organizationId, true)

    assertEquals(expected, actual)
  }

  @Test
  fun `find all species in use returns species used in plantings`() {
    val created =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "batch"))
    store.createSpecies(
        NewSpeciesModel(id = null, organizationId = organizationId, scientificName = "unused"))

    // create plantings with 'other' species
    insertFacility(id = FacilityId(200))
    insertPlantingSite()
    insertPlantingZone()
    insertPlantingSubzone()
    insertWithdrawal()
    insertDelivery()
    insertPlanting(speciesId = created)

    // create another org planting
    val otherOrgId = insertOrganization(id = OrganizationId(2))
    val other =
        store.createSpecies(
            NewSpeciesModel(id = null, organizationId = otherOrgId, scientificName = "other"))
    insertFacility(id = FacilityId(300))
    insertPlantingSite()
    insertPlantingZone()
    insertPlantingSubzone()
    insertWithdrawal()
    insertDelivery()
    insertPlanting(speciesId = other)

    val expected = listOf(store.fetchSpeciesById(created))
    val actual = store.findAllSpecies(organizationId, true)

    assertEquals(expected, actual)
  }
}
