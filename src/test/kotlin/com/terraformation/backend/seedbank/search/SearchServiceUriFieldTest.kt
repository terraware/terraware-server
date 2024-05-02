package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class SearchServiceUriFieldTest : SearchServiceTest() {
  private lateinit var event1: EventId
  private lateinit var event2: EventId

  @BeforeEach
  fun insertEvents() {
    val moduleId = insertModule()
    event1 = insertEvent(moduleId = moduleId, meetingUrl = "https://terraformation.com")
    event2 = insertEvent(moduleId = moduleId, meetingUrl = "https://terraware.io")

    every { user.canReadAllAcceleratorDetails() } returns true
  }

  @Test
  fun `returns uri values`() {
    val prefix = SearchFieldPrefix(tables.events)
    val fields = listOf(prefix.resolve("id"), prefix.resolve("meetingUrl"))
    val sortOrder = fields.map { SearchSortField(it) }

    val result = searchService.search(prefix, fields, NoConditionNode(), sortOrder)

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to event1.toString(), "meetingUrl" to "https://terraformation.com"),
                mapOf("id" to event2.toString(), "meetingUrl" to "https://terraware.io")),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `accepts string values as search criteria`() {
    val prefix = SearchFieldPrefix(tables.events)
    val fields = listOf(prefix.resolve("id"), prefix.resolve("meetingUrl"))

    val criteria1 = FieldNode(prefix.resolve("meetingUrl"), listOf("terra"))
    val criteria2 = FieldNode(prefix.resolve("meetingUrl"), listOf("formation"))

    val result1 = searchService.search(prefix, fields, criteria1)
    val result2 = searchService.search(prefix, fields, criteria2)

    val expected1 =
        SearchResults(
            listOf(
                mapOf("id" to event1.toString(), "meetingUrl" to "https://terraformation.com"),
                mapOf("id" to event2.toString(), "meetingUrl" to "https://terraware.io")),
            cursor = null)

    val expected2 =
        SearchResults(
            listOf(mapOf("id" to event1.toString(), "meetingUrl" to "https://terraformation.com")),
            cursor = null)

    assertEquals(expected1, result1)
    assertEquals(expected2, result2)
  }
}
