package com.terraformation.backend.seedbank.search

import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFilterType
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceFetchValuesTest : SearchServiceTest() {
  @Test
  fun `no criteria for simple column value`() {
    val values = searchService.fetchValues(rootPrefix, speciesNameField, NoConditionNode())
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `renders null values as null, not as a string`() {
    accessionsDao.update(accessionsDao.fetchOneById(AccessionId(1000))!!.copy(speciesId = null))
    val values = searchService.fetchValues(rootPrefix, speciesNameField, NoConditionNode())
    assertEquals(listOf("Other Dogwood", null), values)
  }

  @Test
  fun `fuzzy search of accession number`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `prefix search of accession number`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            FieldNode(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Other Dogwood"), values)
  }

  @Test
  fun `fuzzy search of text field in secondary table`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fuzzy search of text field in secondary table not in field list`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            stateField,
            FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy))
    assertEquals(listOf("Processed", "Processing"), values)
  }

  @Test
  fun `exact search of integer column value`() {
    val values =
        searchService.fetchValues(
            rootPrefix, treesCollectedFromField, FieldNode(treesCollectedFromField, listOf("1")))

    assertEquals(listOf("1"), values)
  }

  @Test
  fun `no criteria for computed column value`() {
    val values = searchService.fetchValues(rootPrefix, activeField, NoConditionNode())
    assertEquals(listOf("Active"), values)
  }

  @Test
  fun `can filter on computed column value`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(stateId = AccessionState.Withdrawn))
    val values =
        searchService.fetchValues(
            rootPrefix, activeField, FieldNode(activeField, listOf("Inactive")))
    assertEquals(listOf("Inactive"), values)
  }

  @Test
  fun `only includes values from accessions the user has permission to view`() {
    insertFacility(1100)

    insertAccession(facilityId = 1100, treesCollectedFrom = 3)

    val expected = listOf("1", "2")

    val actual = searchService.fetchValues(rootPrefix, treesCollectedFromField, NoConditionNode())

    assertEquals(expected, actual)
  }

  @Test
  fun `includes values from accessions at multiple facilities`() {
    every { user.facilityRoles } returns
        mapOf(facilityId to Role.MANAGER, FacilityId(1100) to Role.CONTRIBUTOR)

    insertFacility(1100)

    insertAccession(facilityId = 1100, treesCollectedFrom = 3)

    val expected = listOf("1", "2", "3")

    val actual = searchService.fetchValues(rootPrefix, treesCollectedFromField, NoConditionNode())

    assertEquals(expected, actual)
  }
}
