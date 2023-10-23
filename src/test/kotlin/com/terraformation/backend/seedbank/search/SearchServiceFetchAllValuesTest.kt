package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionCollectorsRow
import com.terraformation.backend.db.seedbank.tables.pojos.BagsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestResultsRow
import com.terraformation.backend.db.seedbank.tables.pojos.ViabilityTestsRow
import io.mockk.every
import java.time.LocalDate
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

internal class SearchServiceFetchAllValuesTest : SearchServiceTest() {
  @Test
  fun `does not return null for non-nullable field`() {
    val values = searchService.fetchAllValues(accessionNumberField, organizationId)
    assertFalse(values.any { it == null }, "List of values should not contain null")
  }

  @Test
  fun `returns values for enum-mapped field`() {
    val expected =
        listOf(null) + ViabilityTestType.entries.map { it.getDisplayName(Locale.ENGLISH) }
    val values = searchService.fetchAllValues(viabilityTestsTypeField, organizationId)
    assertEquals(expected, values)
  }

  @Test
  fun `returns values for free-text field on accession table`() {
    val expected = listOf(null, "Kousa Dogwood", "Other Dogwood")
    val values = searchService.fetchAllValues(speciesNameField, organizationId)
    assertEquals(expected, values)
  }

  @Test
  fun `returns values for field from reference table`() {
    insertSubLocation(1000, name = "Refrigerator 1")
    insertSubLocation(1001, name = "Freezer 1")
    insertSubLocation(1002, name = "Freezer 2")

    val expected = listOf(null, "Freezer 1", "Freezer 2", "Refrigerator 1")
    val values = searchService.fetchAllValues(subLocationNameField, organizationId)
    assertEquals(expected, values)
  }

  @Test
  fun `only includes sub-locations the user has permission to view`() {
    insertFacility(1100)
    insertSubLocation(1000, 100, "Facility 100 fridge")
    insertSubLocation(1001, 1100, "Facility 1100 fridge")

    val expected = listOf(null, "Facility 100 fridge")

    val actual = searchService.fetchAllValues(subLocationNameField, organizationId)

    assertEquals(expected, actual)
  }

  @Test
  fun `only includes accession values the user has permission to view`() {
    insertFacility(1100)

    insertAccession(id = 1100, number = "OtherProject", facilityId = 1100, treesCollectedFrom = 3)

    val expected = listOf(null, "1", "2")

    val actual = searchService.fetchAllValues(plantsCollectedFromField, organizationId)

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
        searchService.fetchAllValues(collectorsNameField, organizationId),
        "Value from accession_collectors table (child of accessions)")

    assertEquals(
        listOf(null, "1,000"),
        searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, organizationId),
        "Value from viability_test_results table (grandchild of accessions)")

    assertEquals(
        listOf(null, "1,000"),
        searchService.fetchAllValues(viabilityTestSeedsTestedField, organizationId),
        "Value from viability_tests table (child of accessions)")
  }

  @Test
  fun `only includes child table values governed by organization search scope`() {
    every { user.facilityRoles } returns
        mapOf(
            facilityId to Role.Contributor,
            FacilityId(1100) to Role.Contributor,
            FacilityId(2200) to Role.Owner)

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
        searchService.fetchAllValues(accessionNumberField, organizationId),
        "Accession numbers for organization $organizationId")
    assertEquals(
        listOf("OtherOrg"),
        searchService.fetchAllValues(accessionNumberField, otherOrganizationId),
        "Accession numbers for organization $otherOrganizationId")
    assertEquals(
        listOf(null, "bag"),
        searchService.fetchAllValues(bagNumberFlattenedField, organizationId),
        "Bag numbers for $organizationId")
    assertEquals(
        listOf(null, "collector 1", "collector 2", "collector 3"),
        searchService.fetchAllValues(rootPrefix.resolve("collectors_name"), organizationId),
        "Collector names for $organizationId")
    assertEquals(
        listOf(null, "notes"),
        searchService.fetchAllValues(rootPrefix.resolve("viabilityTests_notes"), organizationId),
        "Viability test types for $organizationId")
    assertEquals(
        listOf(null, "1"),
        searchService.fetchAllValues(viabilityTestResultsSeedsGerminatedField, organizationId),
        "Seeds germinated for $organizationId")
  }
}
