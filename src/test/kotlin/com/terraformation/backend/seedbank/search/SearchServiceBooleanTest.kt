package com.terraformation.backend.seedbank.search

import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.toGibberish
import com.terraformation.backend.i18n.use
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.NoConditionNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchResults
import com.terraformation.backend.search.SearchSortField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceBooleanTest : SearchServiceTest() {
  @Test
  fun `returns localized boolean values`() {
    val prefix = SearchFieldPrefix(tables.species)
    val fields = listOf(prefix.resolve("id"), prefix.resolve("rare"))
    val sortOrder = fields.map { SearchSortField(it) }

    val result =
        Locales.GIBBERISH.use {
          searchService.search(prefix, fields, mapOf(prefix to NoConditionNode()), sortOrder)
        }

    val expected =
        SearchResults(
            listOf(
                mapOf("id" to "$speciesId1", "rare" to "false".toGibberish()),
                mapOf("id" to "$speciesId2", "rare" to "true".toGibberish()),
            )
        )

    assertEquals(expected, result)
  }

  @Test
  fun `accepts localized boolean values as search criteria`() {
    val prefix = SearchFieldPrefix(tables.species)
    val fields = listOf(prefix.resolve("id"))
    val criteria = FieldNode(prefix.resolve("rare"), listOf("true".toGibberish()))

    val result =
        Locales.GIBBERISH.use { searchService.search(prefix, fields, mapOf(prefix to criteria)) }

    val expected = SearchResults(listOf(mapOf("id" to "$speciesId2")))

    assertEquals(expected, result)
  }
}
