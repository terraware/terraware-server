package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFilterType
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceFetchValuesTest : SearchServiceTest() {
  @Test
  fun `no criteria for simple column value`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(rootPrefix to NoConditionNode()),
        )
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `renders null values as null, not as a string`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(speciesId = null))
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(rootPrefix to NoConditionNode()),
        )
    assertEquals(listOf("Other Dogwood", null), values)
  }

  @Test
  fun `fuzzy search of accession number with value that is not an exact match`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(
                rootPrefix to
                    FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.Fuzzy)
            ),
        )
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `exact-or-fuzzy search of accession number with value that is not an exact substring match`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(
                rootPrefix to
                    FieldNode(accessionNumberField, listOf("xyzz"), SearchFilterType.ExactOrFuzzy)
            ),
        )
    assertEquals(listOf("Kousa Dogwood"), values)
  }

  @Test
  fun `exact-or-fuzzy search of accession number with value that is an exact substring match`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(number = "ABCD"))
    accessionsDao.update(accessionsDao.fetchOneById(accessionId2)!!.copy(number = "ABCEF"))
    insertAccession(
        AccessionsRow(
            number = "ZABCDY",
            stateId = AccessionState.Processing,
            speciesId = speciesId2,
            treesCollectedFrom = 2,
        )
    )
    val values =
        searchService.fetchValues(
            rootPrefix,
            accessionNumberField,
            mapOf(
                rootPrefix to
                    FieldNode(accessionNumberField, listOf("abcd"), SearchFilterType.ExactOrFuzzy)
            ),
        )
    assertEquals(listOf("ABCD", "ZABCDY"), values)
  }

  @Test
  fun `exact-or-fuzzy search of collection site name with value that is an exact substring match`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(collectionSiteName = "Location 10")
    )
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId2)!!.copy(collectionSiteName = "Location 11")
    )
    insertAccession(
        AccessionsRow(
            number = "IJK",
            stateId = AccessionState.Processing,
            speciesId = speciesId2,
            treesCollectedFrom = 2,
            collectionSiteName = "Location 2",
        )
    )
    val values =
        searchService.fetchValues(
            rootPrefix,
            collectionSiteNameField,
            mapOf(
                rootPrefix to
                    FieldNode(
                        collectionSiteNameField,
                        listOf("location 1"),
                        SearchFilterType.ExactOrFuzzy,
                    )
            ),
        )
    assertEquals(listOf("Location 10", "Location 11"), values)
  }

  @Test
  fun `prefix search of accession number`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(
                rootPrefix to FieldNode(accessionNumberField, listOf("a"), SearchFilterType.Fuzzy)
            ),
        )
    assertEquals(listOf("Other Dogwood"), values)
  }

  @Test
  fun `fuzzy search of text field in secondary table`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            speciesNameField,
            mapOf(
                rootPrefix to FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy)
            ),
        )
    assertEquals(listOf("Kousa Dogwood", "Other Dogwood"), values)
  }

  @Test
  fun `fuzzy search of text field in secondary table not in field list`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            stateField,
            mapOf(
                rootPrefix to FieldNode(speciesNameField, listOf("dogwod"), SearchFilterType.Fuzzy)
            ),
        )
    assertEquals(listOf("In Storage", "Processing"), values)
  }

  @Test
  fun `exact search of integer column value`() {
    val values =
        searchService.fetchValues(
            rootPrefix,
            plantsCollectedFromField,
            mapOf(rootPrefix to FieldNode(plantsCollectedFromField, listOf("1"))),
        )

    assertEquals(listOf("1"), values)
  }

  @Test
  fun `no criteria for computed column value`() {
    val values =
        searchService.fetchValues(rootPrefix, activeField, mapOf(rootPrefix to NoConditionNode()))
    assertEquals(listOf("Active"), values)
  }

  @Test
  fun `can filter on computed column value`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(accessionId1)!!.copy(stateId = AccessionState.UsedUp)
    )
    val values =
        searchService.fetchValues(
            rootPrefix,
            activeField,
            mapOf(rootPrefix to FieldNode(activeField, listOf("Inactive"))),
        )
    assertEquals(listOf("Inactive"), values)
  }

  @Test
  fun `only includes values from accessions the user has permission to view`() {
    val otherFacilityId = insertFacility()

    insertAccession(facilityId = otherFacilityId, treesCollectedFrom = 3)

    val expected = listOf("1", "2")

    val actual =
        searchService.fetchValues(
            rootPrefix,
            plantsCollectedFromField,
            mapOf(rootPrefix to NoConditionNode()),
        )

    assertEquals(expected, actual)
  }

  @Test
  fun `includes values from accessions at multiple facilities`() {
    val otherFacilityId = insertFacility()

    every { user.facilityRoles } returns
        mapOf(facilityId to Role.Manager, otherFacilityId to Role.Contributor)

    insertAccession(facilityId = otherFacilityId, treesCollectedFrom = 3)

    val expected = listOf("1", "2", "3")

    val actual =
        searchService.fetchValues(
            rootPrefix,
            plantsCollectedFromField,
            mapOf(rootPrefix to NoConditionNode()),
        )

    assertEquals(expected, actual)
  }
}
