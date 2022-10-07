package com.terraformation.backend.seedbank.search

import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class SearchServiceDateFieldSearchTest : SearchServiceTest() {
  @BeforeEach
  fun insertReceivedDateExamples() {
    listOf(1, 2, 8).forEach { day ->
      insertAccession(number = "JAN$day", receivedDate = LocalDate.of(2021, 1, day))
    }
  }

  @Test
  fun `can search for exact date`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact)

    val expected = SearchResults(listOf(mapOf("id" to "2", "accessionNumber" to "JAN2")), null)
    val actual = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, actual)
  }

  @Test
  fun `can search for missing date`() {
    val fields = listOf(accessionNumberField)
    val searchNode =
        FieldNode(receivedDateField, listOf("2021-01-02", null), SearchFilterType.Exact)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1001", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "2", "accessionNumber" to "JAN2"),
                mapOf("id" to "1000", "accessionNumber" to "XYZ"),
            ),
            null)
    val actual = searchAccessions(facilityId, fields, searchNode)

    assertEquals(expected, actual)
  }

  @Test
  fun `can search by date range`() {
    val fields = listOf(accessionNumberField)
    val sortOrder = listOf(SearchSortField(receivedDateField))
    val searchNode =
        FieldNode(receivedDateField, listOf("2021-01-02", "2021-01-15"), SearchFilterType.Range)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "2", "accessionNumber" to "JAN2"),
                mapOf("id" to "3", "accessionNumber" to "JAN8")),
            null)
    val actual = searchAccessions(facilityId, fields, searchNode, sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `can search by date range with only minimum`() {
    val fields = listOf(accessionNumberField)
    val sortOrder = listOf(SearchSortField(receivedDateField))
    val searchNode =
        FieldNode(receivedDateField, listOf("2021-01-07", null), SearchFilterType.Range)

    val expected = SearchResults(listOf(mapOf("id" to "3", "accessionNumber" to "JAN8")), null)
    val actual = searchAccessions(facilityId, fields, searchNode, sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `can search by date range with only maximum`() {
    val fields = listOf(accessionNumberField)
    val sortOrder = listOf(SearchSortField(receivedDateField))
    val searchNode =
        FieldNode(receivedDateField, listOf(null, "2021-01-03"), SearchFilterType.Range)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "1", "accessionNumber" to "JAN1"),
                mapOf("id" to "2", "accessionNumber" to "JAN2")),
            null)
    val actual = searchAccessions(facilityId, fields, searchNode, sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `date range with two nulls is rejected`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(receivedDateField, listOf(null, null), SearchFilterType.Range)

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }

  @Test
  fun `malformed dates are rejected`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(receivedDateField, listOf("NOT_A_DATE"), SearchFilterType.Exact)

    assertThrows<IllegalArgumentException> { searchAccessions(facilityId, fields, searchNode) }
  }
}
