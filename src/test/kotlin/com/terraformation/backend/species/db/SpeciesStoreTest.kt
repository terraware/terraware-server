package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.GrowthForm
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.SeedStorageBehavior
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.SpeciesProblemField
import com.terraformation.backend.db.SpeciesProblemType
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.tables.pojos.SpeciesProblemsRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.mockUser
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class SpeciesStoreTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  override val user: TerrawareUser = mockUser()

  private lateinit var store: SpeciesStore

  @BeforeEach
  fun setUp() {
    store = SpeciesStore(clock, dslContext, speciesDao, speciesProblemsDao)

    every { clock.instant() } returns Instant.EPOCH

    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `createSpecies inserts species`() {
    val row =
        SpeciesRow(
            commonName = "common",
            deletedBy = user.userId,
            deletedTime = Instant.EPOCH,
            endangered = true,
            familyName = "family",
            growthFormId = GrowthForm.Shrub,
            organizationId = organizationId,
            rare = false,
            scientificName = "test",
            seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
        )

    val speciesId = store.createSpecies(row)
    assertNotNull(speciesId, "Should have returned ID")

    val expected =
        listOf(
            row.copy(
                id = speciesId,
                initialScientificName = "test",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                deletedBy = null,
                deletedTime = null,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
            ))

    val actual = speciesDao.findAll()
    assertEquals(expected, actual)
  }

  @Test
  fun `createSpecies does not modify input`() {
    val originalRow =
        SpeciesRow(
            organizationId = organizationId,
            scientificName = "test",
        )
    val parameterRow = originalRow.copy()

    store.createSpecies(parameterRow)
    assertEquals(originalRow, parameterRow)
  }

  @Test
  fun `createSpecies allows the same name to be used in different organizations`() {
    val otherOrgId = OrganizationId(2)
    insertOrganization(otherOrgId.value)

    val row = SpeciesRow(scientificName = "test")
    store.createSpecies(row.copy(organizationId = organizationId))

    assertDoesNotThrow { store.createSpecies(row.copy(organizationId = otherOrgId)) }
  }

  @Test
  fun `createSpecies reuses previously deleted species if one exists`() {
    val originalSpeciesId =
        store.createSpecies(
            SpeciesRow(
                commonName = "original common",
                endangered = false,
                familyName = "original family",
                organizationId = organizationId,
                scientificName = "test",
                growthFormId = GrowthForm.Fern,
                rare = false,
                seedStorageBehaviorId = SeedStorageBehavior.Orthodox,
            ))
    val originalRow = speciesDao.fetchOneById(originalSpeciesId)!!

    store.deleteSpecies(originalSpeciesId)

    val editedRow =
        SpeciesRow(
            commonName = "edited common",
            endangered = true,
            familyName = "edited family",
            organizationId = organizationId,
            scientificName = "test",
            growthFormId = GrowthForm.Shrub,
            rare = true,
            seedStorageBehaviorId = SeedStorageBehavior.Recalcitrant,
        )

    val newInstant = Instant.ofEpochSecond(500)
    every { clock.instant() } returns newInstant

    val reusedSpeciesId = store.createSpecies(editedRow)

    val expected =
        editedRow.copy(
            createdBy = originalRow.createdBy,
            createdTime = originalRow.createdTime,
            id = originalSpeciesId,
            initialScientificName = "test",
            modifiedBy = user.userId,
            modifiedTime = clock.instant())
    val actual = speciesDao.fetchOneById(reusedSpeciesId)

    assertEquals(expected, actual)
  }

  @Test
  fun `createSpecies throws exception if name already exists for organization`() {
    val row = SpeciesRow(organizationId = organizationId, scientificName = "test")
    store.createSpecies(row)

    assertThrows<DuplicateKeyException> { store.createSpecies(row) }
  }

  @Test
  fun `createSpecies throws exception if user has no permission to create species`() {
    every { user.canCreateSpecies(organizationId) } returns false
    assertThrows<AccessDeniedException> {
      store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "dummy"))
    }
  }

  @Test
  fun `updateSpecies updates all modifiable fields`() {
    val initial =
        SpeciesRow(
            commonName = "original common",
            endangered = true,
            familyName = "original family",
            growthFormId = GrowthForm.Shrub,
            organizationId = organizationId,
            rare = true,
            scientificName = "original scientific",
            seedStorageBehaviorId = SeedStorageBehavior.Unknown,
        )
    val speciesId = store.createSpecies(initial)

    val bogusOrganizationId = OrganizationId(10000)
    val bogusInstant = Instant.ofEpochSecond(1000)
    val bogusUserId = UserId(10000)

    val newInstant = Instant.ofEpochSecond(500)
    every { clock.instant() } returns newInstant

    val update =
        SpeciesRow(
            commonName = "new common",
            createdBy = bogusUserId,
            createdTime = bogusInstant,
            deletedBy = bogusUserId,
            deletedTime = bogusInstant,
            endangered = false,
            familyName = "new family",
            growthFormId = GrowthForm.Fern,
            id = speciesId,
            initialScientificName = "new initial",
            modifiedBy = bogusUserId,
            modifiedTime = bogusInstant,
            organizationId = bogusOrganizationId,
            rare = false,
            scientificName = "new scientific",
            seedStorageBehaviorId = SeedStorageBehavior.Orthodox,
        )

    val expected =
        update.copy(
            createdBy = user.userId,
            createdTime = Instant.EPOCH,
            deletedBy = null,
            deletedTime = null,
            initialScientificName = "original scientific",
            modifiedBy = user.userId,
            modifiedTime = newInstant,
            organizationId = organizationId,
        )

    store.updateSpecies(update)

    val actual = speciesDao.fetchOneById(speciesId)!!
    assertEquals(expected, actual)
  }

  @Test
  fun `updateSpecies throws exception if user has no permission to update species`() {
    every { user.canUpdateSpecies(any()) } returns false

    val speciesId =
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "dummy"))

    assertThrows<AccessDeniedException> {
      store.updateSpecies(SpeciesRow(id = speciesId, scientificName = "other"))
    }
  }

  @Test
  fun `deleteSpecies marks species as deleted`() {
    // Make sure it only deletes the species in question, not the whole table
    store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "other"))
    val expected = store.findAllSpecies(organizationId)

    val speciesId =
        store.createSpecies(
            SpeciesRow(organizationId = organizationId, scientificName = "to delete"))

    store.deleteSpecies(speciesId)

    val actual = store.findAllSpecies(organizationId)
    assertEquals(expected, actual)
  }

  @Test
  fun `deleteSpecies throws exception if user has no permission to delete species`() {
    every { user.canDeleteSpecies(any()) } returns false

    val speciesId =
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "dummy"))

    assertThrows<AccessDeniedException> { store.deleteSpecies(speciesId) }
  }

  @Test
  fun `deleteSpecies throws exception if species does not exist at all`() {
    assertThrows<SpeciesNotFoundException> { store.deleteSpecies(SpeciesId(1)) }
  }

  @Test
  fun `fetchSpeciesById returns species if it is not deleted`() {
    val speciesId =
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "test"))

    val expected =
        SpeciesRow(
            createdBy = user.userId,
            createdTime = clock.instant(),
            id = speciesId,
            initialScientificName = "test",
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            organizationId = organizationId,
            scientificName = "test",
        )

    val actual = store.fetchSpeciesById(speciesId)
    assertEquals(expected, actual)
  }

  @Test
  fun `fetchSpeciesById throws exception if species is deleted`() {
    val speciesId =
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "test"))
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
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "dummy"))
    store.deleteSpecies(speciesId)

    assertEquals(emptyList<SpeciesId>(), store.fetchUncheckedSpeciesIds(organizationId))
  }

  @Test
  fun `findAllProblems does not return problems with deleted species`() {
    val speciesId =
        store.createSpecies(SpeciesRow(organizationId = organizationId, scientificName = "dummy"))
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
            SpeciesRow(organizationId = organizationId, scientificName = scientificName))

    every { user.canReadOrganization(organizationId) } returns false
    every { user.canReadSpecies(speciesId) } returns false

    assertThrows<OrganizationNotFoundException> {
      store.countSpecies(organizationId, Instant.EPOCH)
    }

    assertThrows<OrganizationNotFoundException> {
      store.fetchSpeciesIdByName(organizationId, scientificName)
    }

    assertThrows<SpeciesNotFoundException> { store.fetchSpeciesById(speciesId) }

    assertThrows<OrganizationNotFoundException> { store.findAllSpecies(organizationId) }
  }
}
