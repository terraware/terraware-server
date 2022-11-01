package com.terraformation.backend.seedbank.search

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import com.terraformation.backend.search.FacilityIdScope
import com.terraformation.backend.search.OrganizationIdScope
import io.mockk.every
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SearchServiceFetchAllValuesTest : SearchServiceTest() {
  @Test
  fun `does not return null for non-nullable field`() {
    val values = searchService.fetchAllValues(accessionNumberField, searchScopes)
    assertFalse(values.any { it == null }, "List of values should not contain null")
  }

  @Test
  fun `returns values for enum-mapped field`() {
    val expected = listOf(null) + ViabilityTestType.values().map { it.displayName }
    val values = searchService.fetchAllValues(viabilityTestsTypeField, searchScopes)
    assertEquals(expected, values)
  }

  @Test
  fun `returns values for free-text field on accession table`() {
    val expected = listOf(null, "Kousa Dogwood", "Other Dogwood")
    val values = searchService.fetchAllValues(speciesNameField, searchScopes)
    assertEquals(expected, values)
  }

  @Test
  fun `returns values for field from reference table`() {
    storageLocationsDao.insert(
        StorageLocationsRow(
            id = StorageLocationId(1000),
            facilityId = facilityId,
            name = "Refrigerator 1",
            conditionId = StorageCondition.Refrigerator,
            createdBy = user.userId,
            createdTime = Instant.now(),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))
    storageLocationsDao.insert(
        StorageLocationsRow(
            id = StorageLocationId(1001),
            facilityId = facilityId,
            name = "Freezer 1",
            conditionId = StorageCondition.Freezer,
            createdBy = user.userId,
            createdTime = Instant.now(),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))
    storageLocationsDao.insert(
        StorageLocationsRow(
            id = StorageLocationId(1002),
            facilityId = facilityId,
            name = "Freezer 2",
            conditionId = StorageCondition.Freezer,
            createdBy = user.userId,
            createdTime = Instant.now(),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))

    val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
    val values = searchService.fetchAllValues(storageLocationNameField, searchScopes)
    assertEquals(expected, values)
  }

  @Test
  fun `only includes storage locations the user has permission to view`() {
    insertFacility(1100)

    storageLocationsDao.insert(
        StorageLocationsRow(
            id = StorageLocationId(1000),
            facilityId = FacilityId(100),
            name = "Facility 100 fridge",
            conditionId = StorageCondition.Refrigerator,
            createdBy = user.userId,
            createdTime = Instant.now(),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))
    storageLocationsDao.insert(
        StorageLocationsRow(
            id = StorageLocationId(1001),
            facilityId = FacilityId(1100),
            name = "Facility 1100 fridge",
            conditionId = StorageCondition.Refrigerator,
            createdBy = user.userId,
            createdTime = Instant.now(),
            modifiedBy = user.userId,
            modifiedTime = Instant.now(),
        ))

    val expected = listOf(null, "Facility 100 fridge")

    val actual = searchService.fetchAllValues(storageLocationNameField, searchScopes)

    assertEquals(expected, actual)
  }

  @Test
  fun `only includes accession values the user has permission to view`() {
    insertFacility(1100)

    insertAccession(id = 1100, number = "OtherProject", facilityId = 1100, treesCollectedFrom = 3)

    val expected = listOf(null, "1", "2")

    val actual = searchService.fetchAllValues(treesCollectedFromField, searchScopes)

    assertEquals(expected, actual)
  }

  @Test
  fun `only includes child table values the user has permission to view`() {
    val collectorsNameField = rootPrefix.resolve("collectors_name")
    val hiddenAccessionId = AccessionId(1100)

    insertFacility(1100)

    insertAccession(
        id = hiddenAccessionId, number = "OtherProject", facilityId = 1100, treesCollectedFrom = 3)

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(
            accessionId = hiddenAccessionId, name = "hidden collector", position = 0))

    listOf(1000, 1100).forEach { id ->
      val accessionId = AccessionId(id.toLong())
      val testId = ViabilityTestId(id.toLong())

      viabilityTestsDao.insert(
          ViabilityTestsRow(
              id = testId,
              accessionId = accessionId,
              testType = ViabilityTestType.Lab,
              seedsSown = id))
      viabilityTestResultsDao.insert(
          ViabilityTestResultsRow(
              testId = testId, recordingDate = LocalDate.EPOCH, seedsGerminated = id))
    }

    assertEquals(
        listOf(null, "collector 1", "collector 2", "collector 3"),
        searchService.fetchAllValues(collectorsNameField, searchScopes),
        "Value from accession_collectors table (child of accessions)")

    assertEquals(
        listOf(null, "1000"),
        searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, searchScopes),
        "Value from viability_test_results table (grandchild of accessions)")

    assertEquals(
        listOf(null, "1000"),
        searchService.fetchAllValues(viabilityTestSeedsTestedField, searchScopes),
        "Value from viability_tests table (child of accessions)")
  }

  @Test
  fun `only includes child table values governed by organization search scope`() {
    every { user.facilityRoles } returns
        mapOf(
            facilityId to Role.CONTRIBUTOR,
            FacilityId(1100) to Role.CONTRIBUTOR,
            FacilityId(2200) to Role.OWNER)

    insertFacility(1100)

    val otherOrganizationId = OrganizationId(5)
    insertOrganization(otherOrganizationId)
    insertFacility(2200, otherOrganizationId)

    val accessionId = AccessionId(1100)
    val otherAccessionId = AccessionId(2200)
    val viabilityTestId = ViabilityTestId(1100)
    val otherViabilityTestId = ViabilityTestId(2200)

    insertAccession(id = accessionId, number = "OtherFacility", facilityId = 1100)
    insertAccession(id = otherAccessionId, number = "OtherOrg", facilityId = 2200)
    accessionCollectorsDao.insert(
        AccessionCollectorsRow(
            accessionId = otherAccessionId, position = 0, name = "otherCollector"))
    bagsDao.insert(
        BagsRow(accessionId = accessionId, bagNumber = "bag"),
        BagsRow(accessionId = otherAccessionId, bagNumber = "otherBag"))
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            accessionId = accessionId,
            id = viabilityTestId,
            notes = "notes",
            testType = ViabilityTestType.Lab),
        ViabilityTestsRow(
            accessionId = otherAccessionId,
            id = otherViabilityTestId,
            notes = "otherNotes",
            testType = ViabilityTestType.Nursery))
    viabilityTestResultsDao.insert(
        ViabilityTestResultsRow(
            recordingDate = LocalDate.EPOCH, testId = viabilityTestId, seedsGerminated = 1),
        ViabilityTestResultsRow(
            recordingDate = LocalDate.EPOCH, testId = otherViabilityTestId, seedsGerminated = 2))

    assertEquals(
        listOf("ABCDEFG", "OtherFacility", "XYZ"),
        searchService.fetchAllValues(accessionNumberField, searchScopes),
        "Accession numbers for organization $organizationId")
    assertEquals(
        listOf("OtherOrg"),
        searchService.fetchAllValues(
            accessionNumberField, listOf(OrganizationIdScope(otherOrganizationId))),
        "Accession numbers for organization $otherOrganizationId")
    assertEquals(
        listOf(null, "bag"),
        searchService.fetchAllValues(bagNumberFlattenedField, searchScopes),
        "Bag numbers for $organizationId")
    assertEquals(
        listOf(null, "collector 1", "collector 2", "collector 3"),
        searchService.fetchAllValues(rootPrefix.resolve("collectors_name"), searchScopes),
        "Collector names for $organizationId")
    assertEquals(
        listOf(null, "notes"),
        searchService.fetchAllValues(rootPrefix.resolve("viabilityTests_notes"), searchScopes),
        "Viability test types for $organizationId")
    assertEquals(
        listOf(null, "1"),
        searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, searchScopes),
        "Seeds germinated for $organizationId")
  }

  @Test
  fun `only includes child table values governed by facility search scope`() {
    val otherFacilityId = FacilityId(2200)
    val accessionId = AccessionId(1100)
    val otherAccessionId = AccessionId(2200)
    val viabilityTestId = ViabilityTestId(1100)
    val otherViabilityTestId = ViabilityTestId(2200)

    every { user.facilityRoles } returns
        mapOf(facilityId to Role.CONTRIBUTOR, otherFacilityId to Role.CONTRIBUTOR)

    insertFacility(otherFacilityId)

    insertAccession(id = accessionId, number = "OtherProject")
    insertAccession(id = otherAccessionId, number = "OtherProject22", facilityId = otherFacilityId)

    accessionCollectorsDao.insert(
        AccessionCollectorsRow(
            accessionId = otherAccessionId, position = 0, name = "otherCollector"))
    bagsDao.insert(
        BagsRow(accessionId = accessionId, bagNumber = "bag"),
        BagsRow(accessionId = otherAccessionId, bagNumber = "otherBag"))
    viabilityTestsDao.insert(
        ViabilityTestsRow(
            accessionId = accessionId,
            id = viabilityTestId,
            notes = "notes",
            testType = ViabilityTestType.Lab),
        ViabilityTestsRow(
            accessionId = otherAccessionId,
            id = otherViabilityTestId,
            notes = "otherNotes",
            testType = ViabilityTestType.Nursery))
    viabilityTestResultsDao.insert(
        ViabilityTestResultsRow(
            recordingDate = LocalDate.EPOCH, testId = viabilityTestId, seedsGerminated = 1),
        ViabilityTestResultsRow(
            recordingDate = LocalDate.EPOCH, testId = otherViabilityTestId, seedsGerminated = 2))

    val facilityIdScopes = listOf(FacilityIdScope(facilityId))

    assertEquals(
        listOf("OtherProject22"),
        searchService.fetchAllValues(
            accessionNumberField, listOf(FacilityIdScope(otherFacilityId))),
        "Accession numbers for facility $otherFacilityId")
    assertEquals(
        listOf(null, "bag"),
        searchService.fetchAllValues(bagNumberFlattenedField, facilityIdScopes),
        "Bag numbers for $facilityId")
    assertEquals(
        listOf(null, "collector 1", "collector 2", "collector 3"),
        searchService.fetchAllValues(rootPrefix.resolve("collectors_name"), facilityIdScopes),
        "Collector names for $facilityId")
    assertEquals(
        listOf(null, "notes"),
        searchService.fetchAllValues(rootPrefix.resolve("viabilityTests_notes"), facilityIdScopes),
        "Viability test types for $facilityId")
    assertEquals(
        listOf(null, "1"),
        searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, facilityIdScopes),
        "Seeds germinated for $facilityId")
  }

  @Test
  fun `throws exception if search scopes is empty`() {
    assertThrows<IllegalArgumentException> {
      searchService.fetchAllValues(accessionNumberField, emptyList())
    }
  }
}
