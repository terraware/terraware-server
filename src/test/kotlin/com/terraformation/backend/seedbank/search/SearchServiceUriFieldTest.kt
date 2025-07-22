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
  private lateinit var event3: EventId

  private lateinit var searchResult1: Map<String, Any>
  private lateinit var searchResult2: Map<String, Any>
  private lateinit var searchResult3: Map<String, Any>

  @BeforeEach
  fun insertEvents() {
    val moduleId = insertModule()
    event1 = insertEvent(moduleId = moduleId, meetingUrl = "https://terraformation.com")
    event2 = insertEvent(moduleId = moduleId, meetingUrl = "https://terraware.io")
    event3 = insertEvent(moduleId = moduleId, meetingUrl = null)

    searchResult1 = mapOf("id" to event1.toString(), "meetingUrl" to "https://terraformation.com")
    searchResult2 = mapOf("id" to event2.toString(), "meetingUrl" to "https://terraware.io")
    searchResult3 = mapOf("id" to event3.toString())

    every { user.canReadAllAcceleratorDetails() } returns true
  }

  @Test
  fun `returns uri values`() {
    val prefix = SearchFieldPrefix(tables.events)
    val fields = listOf(prefix.resolve("id"), prefix.resolve("meetingUrl"))
    val sortOrder = fields.map { SearchSortField(it) }

    val result = searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()), sortOrder)
    val expected = SearchResults(listOf(searchResult1, searchResult2, searchResult3), cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `accepts string or null values as search criteria`() {
    val prefix = SearchFieldPrefix(tables.events)
    val fields = listOf(prefix.resolve("id"), prefix.resolve("meetingUrl"))

    val criteria1 = FieldNode(prefix.resolve("meetingUrl"), listOf("terra"))
    val criteria2 = FieldNode(prefix.resolve("meetingUrl"), listOf("formation"))
    val criteria3 = FieldNode(prefix.resolve("meetingUrl"), listOf(null))

    val result1 = searchService.search(prefix, fields, mapOf(prefix to criteria1))
    val result2 = searchService.search(prefix, fields, mapOf(prefix to criteria2))
    val result3 = searchService.search(prefix, fields, mapOf(prefix to criteria3))

    val expected1 = SearchResults(listOf(searchResult1, searchResult2), cursor = null)
    val expected2 = SearchResults(listOf(searchResult1))
    val expected3 = SearchResults(listOf(searchResult3))

    assertEquals(expected1, result1)
    assertEquals(expected2, result2)
    assertEquals(expected3, result3)
  }
}
