package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ScientificNameExistsException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.default_schema.EcosystemType
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
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
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
            deletedTime = Instant.EPOCH,
            endangered = true,
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
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                deletedBy = null,
                deletedTime = null,
                endangered = true,
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
                ecosystemTypes = setOf(EcosystemType.Mangroves),
                endangered = false,
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
            ecosystemTypes = setOf(EcosystemType.Tundra),
            endangered = true,
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
            createdBy = originalRow.createdBy,
            createdTime = originalRow.createdTime,
            endangered = true,
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
            ecosystemTypes = setOf(EcosystemType.Mangroves, EcosystemType.Tundra),
            endangered = true,
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
            deletedTime = bogusInstant,
            ecosystemTypes = setOf(EcosystemType.BorealForestsTaiga, EcosystemType.Tundra),
            endangered = false,
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
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            deletedBy = null,
            deletedTime = null,
            endangered = false,
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

  @Nested
  inner class FetchSpeciesByPlantingSubzoneIds {
    // Test data has two zones, each with two subzones, and some plantings.
    // Zone 1 subzone 1 = species 1
    // Zone 1 subzone 2 = species 1 (from one delivery), species 2 (from two deliveries)
    // Zone 2 subzone 1 = species 2
    // Zone 2 subzone 2 = no plantings

    private val speciesId1 by lazy { insertSpecies(speciesId = 1) }
    private val speciesId2 by lazy { insertSpecies(speciesId = 2) }
    private val speciesModel1 by lazy { store.fetchSpeciesById(speciesId1) }
    private val speciesModel2 by lazy { store.fetchSpeciesById(speciesId2) }
    private val plantingSiteId by lazy { insertPlantingSite() }
    private val plantingZoneId1 by lazy {
      insertPlantingZone(plantingSiteId = plantingSiteId, name = "1")
    }
    private val plantingZoneId2 by lazy {
      insertPlantingZone(plantingSiteId = plantingSiteId, name = "2")
    }
    private val plantingSubzoneId11 by lazy {
      insertPlantingSubzone(plantingZoneId = plantingZoneId1, name = "11")
    }
    private val plantingSubzoneId12 by lazy {
      insertPlantingSubzone(plantingZoneId = plantingZoneId1, name = "12")
    }
    private val plantingSubzoneId21 by lazy {
      insertPlantingSubzone(plantingZoneId = plantingZoneId2, name = "21")
    }
    private val plantingSubzoneId22 by lazy {
      insertPlantingSubzone(plantingZoneId = plantingZoneId2, name = "22")
    }

    @BeforeEach
    fun insertPlantings() {
      val batchId1 = insertBatch(speciesId = speciesId1)
      val batchId2 = insertBatch(speciesId = speciesId2)
      val withdrawalId1 = insertWithdrawal()
      val withdrawalId2 = insertWithdrawal()
      val withdrawalId3 = insertWithdrawal()
      val deliveryId1 =
          insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId1)
      val deliveryId2 =
          insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId2)
      val deliveryId3 =
          insertDelivery(plantingSiteId = plantingSiteId, withdrawalId = withdrawalId3)
      insertBatchWithdrawal(batchId = batchId1, withdrawalId = withdrawalId1)
      insertBatchWithdrawal(batchId = batchId2, withdrawalId = withdrawalId1)
      insertPlanting(
          deliveryId = deliveryId1,
          plantingSiteId = plantingSiteId,
          plantingSubzoneId = plantingSubzoneId11,
          speciesId = speciesId1)
      insertPlanting(
          deliveryId = deliveryId1,
          plantingSiteId = plantingSiteId,
          plantingSubzoneId = plantingSubzoneId12,
          speciesId = speciesId2)
      insertPlanting(
          deliveryId = deliveryId2,
          plantingSiteId = plantingSiteId,
          plantingSubzoneId = plantingSubzoneId12,
          speciesId = speciesId1)
      insertPlanting(
          deliveryId = deliveryId2,
          plantingSiteId = plantingSiteId,
          plantingSubzoneId = plantingSubzoneId12,
          speciesId = speciesId2)
      insertPlanting(
          deliveryId = deliveryId3,
          plantingSiteId = plantingSiteId,
          plantingSubzoneId = plantingSubzoneId21,
          speciesId = speciesId2)

      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `includes all subzones by default`() {
      assertEquals(
          mapOf(
              plantingSubzoneId11 to listOf(speciesModel1),
              plantingSubzoneId12 to listOf(speciesModel1, speciesModel2),
              plantingSubzoneId21 to listOf(speciesModel2),
              plantingSubzoneId22 to emptyList(),
          ),
          store.fetchSpeciesByPlantingSubzoneIds(plantingSiteId))
    }

    @Test
    fun `limits results to requested subzones`() {
      assertEquals(
          mapOf(
              plantingSubzoneId12 to listOf(speciesModel1, speciesModel2),
              plantingSubzoneId21 to listOf(speciesModel2),
              plantingSubzoneId22 to emptyList(),
          ),
          store.fetchSpeciesByPlantingSubzoneIds(
              plantingSiteId,
              listOf(plantingSubzoneId12, plantingSubzoneId21, plantingSubzoneId22)))
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      every { user.canReadPlantingSite(any()) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchSpeciesByPlantingSubzoneIds(plantingSiteId)
      }
    }
  }
}
