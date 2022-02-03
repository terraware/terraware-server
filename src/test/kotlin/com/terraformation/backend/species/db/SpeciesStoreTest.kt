package com.terraformation.backend.species.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.UserModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.PlantForm
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.SpeciesNotFoundException
import com.terraformation.backend.db.tables.daos.SpeciesDao
import com.terraformation.backend.db.tables.daos.SpeciesNamesDao
import com.terraformation.backend.db.tables.pojos.SpeciesNamesRow
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import net.postgis.jdbc.geometry.Point
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException

internal class SpeciesStoreTest : DatabaseTest(), RunsAsUser {
  private val clock: Clock = mockk()
  override val user: UserModel = mockk()

  private lateinit var speciesDao: SpeciesDao
  private lateinit var speciesNamesDao: SpeciesNamesDao
  private lateinit var store: SpeciesStore

  @BeforeEach
  fun setUp() {
    val jooqConfig = dslContext.configuration()

    speciesDao = SpeciesDao(jooqConfig)
    speciesNamesDao = SpeciesNamesDao(jooqConfig)

    store = SpeciesStore(clock, dslContext, speciesDao, speciesNamesDao)

    every { clock.instant() } returns Instant.EPOCH

    every { user.canCreateSpecies() } returns true
    every { user.canUpdateSpecies(any()) } returns true
    every { user.canDeleteSpecies(any()) } returns true
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

    val speciesId = store.createSpecies(row)
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
  fun `createSpecies throws exception if name already exists`() {
    val row = SpeciesRow(name = "test")
    store.createSpecies(row)

    assertThrows<DuplicateKeyException> { store.createSpecies(row) }
  }

  @Test
  fun `createSpecies throws exception if user has no permission to create species`() {
    every { user.canCreateSpecies() } returns false
    assertThrows<AccessDeniedException> { store.createSpecies(SpeciesRow(name = "dummy")) }
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
    val speciesId = store.createSpecies(initial)

    val update =
        SpeciesRow(
            id = speciesId,
            plantFormId = PlantForm.Liana,
            conservationStatusId = "CR",
            name = "updated",
            isScientific = true)

    store.updateSpecies(update)

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
    val speciesId = store.createSpecies(SpeciesRow(name = "oldName"))
    val newName =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "newName",
            speciesId = speciesId)
    speciesNamesDao.insert(newName)

    val update = SpeciesRow(id = speciesId, name = "newName", isScientific = true)

    store.updateSpecies(update)

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

    val speciesId = store.createSpecies(SpeciesRow(name = "dummy"))

    assertThrows<AccessDeniedException> {
      store.updateSpecies(SpeciesRow(id = speciesId, name = "other"))
    }
  }

  @Test
  fun `deleteSpecies deletes both species and names`() {
    // Make sure it only deletes the species in question, not the whole table
    store.createSpecies(SpeciesRow(name = "other"))
    val otherSpecies = speciesDao.findAll()
    val otherNames = speciesNamesDao.findAll()

    val speciesId = store.createSpecies(SpeciesRow(name = "dummy"))
    speciesNamesDao.insert(
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            speciesId = speciesId,
            name = "name2"))

    store.deleteSpecies(speciesId)

    assertEquals(otherSpecies, speciesDao.findAll())
    assertEquals(otherNames, speciesNamesDao.findAll())
  }

  @Test
  fun `deleteSpecies throws exception if user has no permission to delete species`() {
    every { user.canDeleteSpecies(any()) } returns false

    val speciesId = store.createSpecies(SpeciesRow(name = "dummy"))

    assertThrows<AccessDeniedException> { store.deleteSpecies(speciesId) }
  }

  @Test
  fun `deleteSpecies throws exception if species does not exist`() {
    assertThrows<SpeciesNotFoundException> { store.deleteSpecies(SpeciesId(1)) }
  }

  @Test
  fun `deleteSpeciesName deletes secondary names`() {
    val speciesId = store.createSpecies(SpeciesRow(name = "primary"))
    val primaryNameOnly = speciesNamesDao.findAll()
    val nameRow =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "secondary",
            speciesId = speciesId,
        )
    speciesNamesDao.insert(nameRow)

    store.deleteSpeciesName(nameRow.id!!)

    assertEquals(primaryNameOnly, speciesNamesDao.findAll())
  }

  @Test
  fun `deleteSpeciesName throws exception if user has no permission to update species`() {
    val speciesId = store.createSpecies(SpeciesRow(name = "dummy"))
    val nameRow =
        SpeciesNamesRow(
            createdTime = clock.instant(),
            modifiedTime = clock.instant(),
            name = "secondary",
            speciesId = speciesId,
        )
    speciesNamesDao.insert(nameRow)

    every { user.canUpdateSpecies(any()) } returns false

    assertThrows<AccessDeniedException> { store.deleteSpeciesName(nameRow.id!!) }
  }

  @Test
  fun `deleteSpeciesName throws exception if primary name is deleted`() {
    store.createSpecies(SpeciesRow(name = "primary"))
    val nameRow = speciesNamesDao.findAll()[0]

    assertThrows<DataIntegrityViolationException> { store.deleteSpeciesName(nameRow.id!!) }
  }
}
