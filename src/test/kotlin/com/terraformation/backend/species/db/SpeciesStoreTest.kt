package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.PlantForm
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNameId
import com.terraformation.backend.db.SpeciesNameNotFoundException
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.daos.SpeciesOptionsDao
import com.terraformation.backend.db.tables.pojos.SpeciesNamesRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.db.tables.references.SPECIES_OPTIONS
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import net.postgis.jdbc.geometry.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class SpeciesStoreTest : DatabaseTest(), RunsAsUser {
  private val organizationId = OrganizationId(1)

  private val clock: Clock = mockk()
  override val user: UserModel = mockk()

  private lateinit var speciesDao: SpeciesDao
  private lateinit var speciesNamesDao: SpeciesNamesDao
  private lateinit var speciesOptionsDao: SpeciesOptionsDao
  private lateinit var store: SpeciesStore

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    speciesDao = SpeciesDao(jooqConfig)
    speciesNamesDao = SpeciesNamesDao(jooqConfig)
    speciesOptionsDao = SpeciesOptionsDao(jooqConfig)

    store =
        SpeciesStore(
            clock,
            dslContext,
            speciesDao,
            speciesNamesDao,
            speciesOptionsDao,
        )

    every { clock.instant() } returns Instant.EPOCH

    every { user.canCreateSpecies(any()) } returns true
    every { user.canReadLayer(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canReadSpecies(any()) } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true
    every { user.canCreateSpeciesName(any()) } returns true
    every { user.canReadSpeciesName(any()) } returns true
    every { user.canUpdateSpeciesName(any()) } returns true
    every { user.canDeleteSpeciesName(any()) } returns true

    insertSiteData()
  }

  @Test
  fun `createSpecies inserts species and name`() {
    val row =
        SpeciesRow(
            name = "test",
            plantFormId = PlantForm.Liana,
            conservationStatusId = "LC",
            rareTypeId = RareType.Unsure,
            isScientific = true,
            nativeRange = Point(1.0, 2.0, 3.0).apply { srid = SRID.SPHERICAL_MERCATOR },
            tsn = "tsn")

    val speciesId = store.createSpecies(organizationId, row)
    assertNotNull(speciesId, "Should have returned ID")

    val species = speciesDao.findAll()
    assertEquals(
        row.copy(id = speciesId),
        species[0].copy(createdTime = null, modifiedTime = null),
        "Should have inserted species")

    val names = speciesNamesDao.findAll()
    assertEquals("test", names[0].name, "Should have inserted name")
  }

  @Test
  fun `createSpecies allows the same name to be used in different organizations`() {
    val otherOrgId = OrganizationId(2)
    insertOrganization(otherOrgId.value)

    val row = SpeciesRow(name = "test")
    store.createSpecies(organizationId, row)

    assertDoesNotThrow { store.createSpecies(otherOrgId, row) }
  }

  @Test
  fun `createSpecies reuses previously deleted species if one exists`() {
    val originalSpeciesId = store.createSpecies(organizationId, SpeciesRow(name = "test"))

    store.deleteSpecies(organizationId, originalSpeciesId)

    val reusedSpeciesId = store.createSpecies(organizationId, SpeciesRow(name = "test"))

    assertEquals(originalSpeciesId, reusedSpeciesId)
  }

  @Test
  fun `createSpecies throws exception if name already exists for organization`() {
    val row = SpeciesRow(name = "test")
    store.createSpecies(organizationId, row)

    assertThrows<DuplicateKeyException> { store.createSpecies(organizationId, row) }
  }

  @Test
  fun `createSpecies throws exception if user has no permission to create species`() {
    every { user.canCreateSpecies(organizationId) } returns false
    assertThrows<AccessDeniedException> {
      store.createSpecies(organizationId, SpeciesRow(name = "dummy"))
    }
  }

  @Test
  fun `updateSpecies updates both species and name`() {
    val initial =
        SpeciesRow(
            name = "initial",
            plantFormId = PlantForm.Shrub,
            conservationStatusId = "LC",
            rareTypeId = RareType.Unsure,
            isScientific = false)
    val speciesId = store.createSpecies(organizationId, initial)

    val update =
        SpeciesRow(
            id = speciesId,
            plantFormId = PlantForm.Liana,
            conservationStatusId = "CR",
            name = "updated",
            isScientific = true)

    store.updateSpecies(organizationId, update)

    val actual = speciesDao.fetchOneById(speciesId)!!
    assertEquals(
        update,
        actual.copy(createdTime = null, modifiedTime = null),
        "Should have updated species data")

    val names = speciesNamesDao.fetchBySpeciesId(speciesId)
    assertEquals(1, names.size, "Should have updated existing name")
    assertEquals(update.name, names[0].name, "Should have updated name")
    assertEquals(
        update.isScientific, names[0].isScientific, "Should have updated isScientific on name")
  }

  @Test
  fun `updateSpecies deletes old primary name if switching to an existing secondary name`() {
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "oldName"))
    val newName =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "newName",
            organizationId = organizationId,
            speciesId = speciesId)
    speciesNamesDao.insert(newName)

    val update = SpeciesRow(id = speciesId, name = "newName", isScientific = true)

    store.updateSpecies(organizationId, update)

    val actual = speciesDao.fetchOneById(speciesId)!!
    assertEquals(update.name, actual.name, "Should have updated primary name")
    assertEquals(
        update.isScientific, actual.isScientific, "Should have updated primary isScientific")

    val names = speciesNamesDao.findAll()
    assertEquals(1, names.size, "Should have deleted old primary name")
    assertEquals(update.name, names[0].name, "Should have updated names row")
    assertEquals(
        update.isScientific, names[0].isScientific, "Should have updated isScientific of name")
  }

  @Test
  fun `updateSpecies throws exception if user has no permission to update species`() {
    every { user.canUpdateSpecies(any()) } returns false

    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "dummy"))

    assertThrows<AccessDeniedException> {
      store.updateSpecies(organizationId, SpeciesRow(id = speciesId, name = "other"))
    }
  }

  @Test
  fun `updateSpeciesName updates primary name if needed`() {
    val speciesId = SpeciesId(1)
    val speciesNameId = SpeciesNameId(1)
    insertSpecies(
        speciesId = speciesId,
        name = "oldName",
        organizationId = organizationId,
        speciesNameId = speciesNameId)

    store.updateSpeciesName(SpeciesNamesRow(id = speciesNameId, name = "newName"))

    val updatedSpeciesRow = speciesDao.fetchOneById(speciesId)!!

    assertEquals("newName", updatedSpeciesRow.name)
  }

  @Test
  fun `updateSpeciesName throws exception if user has no permission to update species names`() {
    val speciesId = SpeciesId(1)
    val speciesNameId = SpeciesNameId(1)
    insertSpecies(speciesId, organizationId = organizationId, speciesNameId = speciesNameId)

    every { user.canUpdateSpeciesName(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.updateSpeciesName(SpeciesNamesRow(id = speciesNameId, name = "newName"))
    }
  }

  @Test
  fun `deleteSpecies deletes options but leaves names and species`() {
    // Make sure it only deletes the species in question, not the whole table
    store.createSpecies(organizationId, SpeciesRow(name = "other"))
    val expectedOptions = speciesOptionsDao.findAll()

    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "dummy"))
    speciesNamesDao.insert(
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            organizationId = organizationId,
            speciesId = speciesId,
            name = "name2"))

    val allNames = speciesNamesDao.findAll()
    val allSpecies = speciesDao.findAll()

    store.deleteSpecies(organizationId, speciesId)

    assertEquals(
        expectedOptions,
        speciesOptionsDao.findAll(),
        "Options row for deleted species should be deleted")
    assertEquals(
        allNames, speciesNamesDao.findAll(), "Names of deleted species should not be deleted")
    assertEquals(allSpecies, speciesDao.findAll(), "Underlying species should not be deleted")
  }

  @Test
  fun `deleteSpecies throws exception if user has no permission to delete species`() {
    every { user.canDeleteSpecies(any()) } returns false

    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "dummy"))

    assertThrows<AccessDeniedException> { store.deleteSpecies(organizationId, speciesId) }
  }

  @Test
  fun `deleteSpecies throws exception if species does not exist at all`() {
    assertThrows<SpeciesNotFoundException> { store.deleteSpecies(organizationId, SpeciesId(1)) }
  }

  @Test
  fun `deleteSpecies throws exception if species does not exist in organization`() {
    val otherOrganizationId = OrganizationId(2)
    val speciesId = SpeciesId(1)
    insertOrganization(otherOrganizationId)
    insertSpecies(speciesId, organizationId = otherOrganizationId)

    assertThrows<SpeciesNotFoundException> { store.deleteSpecies(organizationId, speciesId) }
  }

  @Test
  fun `deleteSpeciesName deletes secondary names`() {
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "primary"))
    val primaryNameOnly = speciesNamesDao.findAll()
    val nameRow =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "secondary",
            organizationId = organizationId,
            speciesId = speciesId,
        )
    speciesNamesDao.insert(nameRow)

    store.deleteSpeciesName(nameRow.id!!)

    assertEquals(primaryNameOnly, speciesNamesDao.findAll())
  }

  @Test
  fun `deleteSpeciesName throws exception if user has no permission to delete species names`() {
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "dummy"))
    val nameRow =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "secondary",
            organizationId = organizationId,
            speciesId = speciesId,
        )
    speciesNamesDao.insert(nameRow)

    every { user.canDeleteSpeciesName(any()) } returns false

    assertThrows<AccessDeniedException> { store.deleteSpeciesName(nameRow.id!!) }
  }

  @Test
  fun `deleteSpeciesName throws exception if primary name is deleted`() {
    store.createSpecies(organizationId, SpeciesRow(name = "primary"))
    val nameRow = speciesNamesDao.findAll()[0]

    assertThrows<DataIntegrityViolationException> { store.deleteSpeciesName(nameRow.id!!) }
  }

  @Test
  fun `getSpeciesId returns existing species ID for organization`() {
    val speciesId = SpeciesId(1)
    val name = "test"

    insertSpecies(speciesId.value, name = name, organizationId = organizationId.value)

    assertEquals(speciesId, store.getOrCreateSpecies(organizationId, name))
  }

  @Test
  fun `getSpeciesId creates new species if name does not exist in organization`() {
    val otherOrgId = OrganizationId(2)
    val otherOrgSpeciesId = SpeciesId(10)
    val name = "test"

    insertOrganization(otherOrgId.value)
    insertSpecies(otherOrgSpeciesId.value, name = name, organizationId = otherOrgId.value)

    assertEquals(
        emptyList<SpeciesNamesRow>(),
        speciesNamesDao.fetchByOrganizationId(organizationId),
        "Species for main organization")

    val speciesId = store.getOrCreateSpecies(organizationId, name)

    assertNotNull(speciesId, "Should have created species")
    assertNotEquals(otherOrgSpeciesId, speciesId, "Should not use species ID from other org")
  }

  @Test
  fun `fetchSpeciesId returns existing species ID for organization that owns the layer`() {
    val otherOrgId = OrganizationId(2)
    insertOrganization(otherOrgId.value)

    val name = "test"
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = name))
    store.createSpecies(otherOrgId, SpeciesRow(name = name))

    val layerId = LayerId(100)

    insertLayer(layerId.value)
    insertFeature(1000)
    insertPlant(1000, speciesId = speciesId)

    assertEquals(speciesId, store.fetchSpeciesId(layerId, name))
  }

  @Test
  fun `fetchSpeciesId throws exception if user has no permission to read layer`() {
    val layerId = LayerId(100)
    insertLayer(layerId.value)

    every { user.canReadLayer(layerId) } returns false

    assertThrows<LayerNotFoundException> { store.fetchSpeciesId(layerId, "test") }
  }

  @Test
  fun `fetchSpeciesById returns species if there is an options row in the organization`() {
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "test"))

    val expected =
        SpeciesRow(
            createdTime = clock.instant(),
            id = speciesId,
            isScientific = false,
            modifiedTime = clock.instant(),
            name = "test",
        )

    val actual = store.fetchSpeciesById(organizationId, speciesId)

    assertEquals(expected, actual)
  }

  @Test
  fun `fetchSpeciesById returns null if there is no options row in the organization`() {
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = "test"))
    dslContext.deleteFrom(SPECIES_OPTIONS).execute()

    assertNull(store.fetchSpeciesById(organizationId, speciesId))
  }

  @Test
  fun `species reading methods throw exception if user has no permission to read organization`() {
    val name = "species"
    val speciesId = store.createSpecies(organizationId, SpeciesRow(name = name))

    every { user.canReadOrganization(organizationId) } returns false
    every { user.canReadSpecies(organizationId) } returns false
    every { user.canReadSpeciesName(any()) } returns false

    assertThrows<OrganizationNotFoundException> {
      store.countSpecies(organizationId, Instant.EPOCH)
    }

    assertThrows<OrganizationNotFoundException> { store.getOrCreateSpecies(organizationId, name) }

    assertThrows<SpeciesNotFoundException> { store.fetchSpeciesById(organizationId, speciesId) }

    assertThrows<SpeciesNameNotFoundException> { store.fetchSpeciesNameById(SpeciesNameId(1)) }

    assertThrows<OrganizationNotFoundException> { store.findAllSpecies(organizationId) }

    assertThrows<OrganizationNotFoundException> { store.findAllSpeciesNames(organizationId) }
  }
}
