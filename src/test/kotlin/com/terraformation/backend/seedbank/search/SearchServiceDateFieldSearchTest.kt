package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
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
  private lateinit var accessionIdJan1: AccessionId
  private lateinit var accessionIdJan2: AccessionId
  private lateinit var accessionIdJan8: AccessionId

  @BeforeEach
  fun insertReceivedDateExamples() {
    accessionIdJan1 = insertAccession(number = "JAN1", receivedDate = LocalDate.of(2021, 1, 1))
    accessionIdJan2 = insertAccession(number = "JAN2", receivedDate = LocalDate.of(2021, 1, 2))
    accessionIdJan8 = insertAccession(number = "JAN8", receivedDate = LocalDate.of(2021, 1, 8))
  }

  @Test
  fun `can search for exact date`() {
    val fields = listOf(accessionNumberField)
    val searchNode = FieldNode(receivedDateField, listOf("2021-01-02"), SearchFilterType.Exact)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionIdJan2", "accessionNumber" to "JAN2")))
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
                mapOf("id" to "$accessionId2", "accessionNumber" to "ABCDEFG"),
                mapOf("id" to "$accessionIdJan2", "accessionNumber" to "JAN2"),
                mapOf("id" to "$accessionId1", "accessionNumber" to "XYZ"),
            )
        )
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
                mapOf("id" to "$accessionIdJan2", "accessionNumber" to "JAN2"),
                mapOf("id" to "$accessionIdJan8", "accessionNumber" to "JAN8"),
            )
        )
    val actual = searchAccessions(facilityId, fields, searchNode, sortOrder)

    assertEquals(expected, actual)
  }

  @Test
  fun `can search by date range with only minimum`() {
    val fields = listOf(accessionNumberField)
    val sortOrder = listOf(SearchSortField(receivedDateField))
    val searchNode =
        FieldNode(receivedDateField, listOf("2021-01-07", null), SearchFilterType.Range)

    val expected =
        SearchResults(listOf(mapOf("id" to "$accessionIdJan8", "accessionNumber" to "JAN8")))
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
                mapOf("id" to "$accessionIdJan1", "accessionNumber" to "JAN1"),
                mapOf("id" to "$accessionIdJan2", "accessionNumber" to "JAN2"),
            )
        )
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
