package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchDirection
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SearchServiceAgeFieldTest : SearchServiceTest() {
  private val ageMonthsField = rootPrefix.resolve("ageMonths")
  private val ageYearsField = rootPrefix.resolve("ageYears")
  private val collectedDateField = rootPrefix.resolve("collectedDate")
  private val idField = rootPrefix.resolve("id")

  @Test
  fun `can search for exact ages`() {
    val accessionIds =
        listOf(
            accessionId1,
            accessionId2,
            insertAccession(stateId = AccessionState.Drying),
            insertAccession(stateId = AccessionState.Drying),
            insertAccession(stateId = AccessionState.Drying),
        )

    setCollectedDates(
        accessionIds[0] to "2018-01-01", // 29 months old
        accessionIds[1] to "2019-01-01", // 17 months old
        accessionIds[2] to "2020-01-01", // 5 months old
        accessionIds[3] to "2020-06-01", // 0 months old
        accessionIds[4] to null,
    )

    val searchNode = FieldNode(ageMonthsField, listOf("0", "17", null))
    val sortField = SearchSortField(ageMonthsField)

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "${accessionIds[3]}",
                    "ageMonths" to "0",
                    "ageYears" to "0",
                    "collectedDate" to "2020-06-01",
                ),
                mapOf(
                    "id" to "${accessionIds[1]}",
                    "ageMonths" to "17",
                    "ageYears" to "1",
                    "collectedDate" to "2019-01-01",
                ),
                mapOf("id" to "${accessionIds[4]}"),
            ))
    val actual =
        searchService.search(
            rootPrefix,
            listOf(idField, ageMonthsField, ageYearsField, collectedDateField),
            mapOf(rootPrefix to searchNode),
            listOf(sortField))

    assertEquals(expected, actual)
  }

  @Test
  fun `sorting by age in months uses underlying date values`() {
    setCollectedDates(accessionId2 to "2020-06-01", accessionId1 to "2020-06-02")

    val searchNode = FieldNode(ageMonthsField, listOf("0"))
    val sortField = SearchSortField(ageMonthsField, SearchDirection.Descending)

    val expected =
        SearchResults(
            listOf(
                // Oldest first, meaning ascending date order
                mapOf("id" to "$accessionId2", "ageMonths" to "0", "collectedDate" to "2020-06-01"),
                mapOf("id" to "$accessionId1", "ageMonths" to "0", "collectedDate" to "2020-06-02"),
            ))

    val actual =
        searchService.search(
            rootPrefix,
            listOf(idField, ageMonthsField, collectedDateField),
            mapOf(rootPrefix to searchNode),
            listOf(sortField))

    assertEquals(expected, actual)
  }

  @Test
  fun `sorting by age in years uses underlying date values`() {
    setCollectedDates(accessionId2 to "2020-06-01", accessionId1 to "2020-06-02")

    val searchNode = FieldNode(ageYearsField, listOf("0"))
    val sortField = SearchSortField(ageYearsField)

    val expected =
        SearchResults(
            listOf(
                // Youngest first, meaning reverse date order
                mapOf("id" to "$accessionId1", "ageYears" to "0", "collectedDate" to "2020-06-02"),
                mapOf("id" to "$accessionId2", "ageYears" to "0", "collectedDate" to "2020-06-01"),
            ))

    val actual =
        searchService.search(
            rootPrefix,
            listOf(idField, ageYearsField, collectedDateField),
            mapOf(rootPrefix to searchNode),
            listOf(sortField))

    assertEquals(expected, actual)
  }

  @Test
  fun `searching for null age returns accessions without collected dates`() {
    setCollectedDates(accessionId1 to null, accessionId2 to "2020-05-31")

    val searchNode = FieldNode(ageMonthsField, listOf(null))

    val expected = SearchResults(listOf(mapOf("id" to "$accessionId1")))
    val actual = searchService.search(rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode))

    assertEquals(expected, actual)
  }

  @Test
  fun `searching for 0 months returns accessions from current month`() {
    setCollectedDates(accessionId1 to "2020-06-01", accessionId2 to "2020-05-31")

    val searchNode = FieldNode(ageMonthsField, listOf("0"))

    val expected = SearchResults(listOf(mapOf("id" to "$accessionId1")))
    val actual = searchService.search(rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode))

    assertEquals(expected, actual)
  }

  @Test
  fun `range search by age in years returns results from entire year`() {
    val otherAccessionId = insertAccession()

    setCollectedDates(
        accessionId1 to "2018-01-01",
        accessionId2 to "2019-12-31",
        otherAccessionId to "2020-01-01")

    val searchNode = FieldNode(ageYearsField, listOf("1", "2"), SearchFilterType.Range)
    val sortField = SearchSortField(ageYearsField)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionId2"), mapOf("id" to "$accessionId1")))
    val actual =
        searchService.search(
            rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode), listOf(sortField))

    assertEquals(expected, actual)
  }

  @Test
  fun `range search with unbounded minimum age returns new matches`() {
    setCollectedDates(accessionId1 to "2020-01-01", accessionId2 to "2020-05-01")

    // "Up to 2 months old"
    val searchNode = FieldNode(ageMonthsField, listOf(null, "2"), SearchFilterType.Range)

    val expected = SearchResults(listOf(mapOf("id" to "$accessionId2")))
    val actual = searchService.search(rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode))

    assertEquals(expected, actual)
  }

  @Test
  fun `range search with unbounded maximum age returns old matches`() {
    setCollectedDates(accessionId1 to "2020-01-01", accessionId2 to "2020-05-01")

    // "At least 3 months old"
    val searchNode = FieldNode(ageMonthsField, listOf("3", null), SearchFilterType.Range)

    val expected = SearchResults(listOf(mapOf("id" to "$accessionId1")))
    val actual = searchService.search(rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode))

    assertEquals(expected, actual)
  }

  @Test
  fun `negative ages are not supported`() {
    val searchNode = FieldNode(ageMonthsField, listOf("-1"))

    assertThrows<IllegalArgumentException> {
      searchService.search(rootPrefix, listOf(idField), mapOf(rootPrefix to searchNode))
    }
  }

  private fun setCollectedDates(vararg dates: Pair<AccessionId, String?>) {
    dates.forEach { (id, dateStr) ->
      val accession = accessionsDao.fetchOneById(id)!!
      val collectedDate = dateStr?.let { LocalDate.parse(it) }
      accessionsDao.update(accession.copy(collectedDate = collectedDate))
    }
  }
}
