package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.i18n.Locales
import com.terraformation.backend.i18n.use
import com.terraformation.backend.search.AndNode
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class SearchServiceRawTest : SearchServiceTest() {
  @Test
  fun `can search for raw and localized fields at the same time`() {
    accessionsDao.update(accessionsDao.fetchOneById(AccessionId(1000))!!.copy(estSeedCount = 12000))

    val fields =
        listOf(rootPrefix.resolve("estimatedCount"), rootPrefix.resolve("estimatedCount(raw)"))
    val criteria = FieldNode(accessionIdField, listOf("1000"))

    val result = searchService.search(rootPrefix, fields, criteria)

    val expected =
        SearchResults(
            listOf(
                mapOf("estimatedCount" to "12,000", "estimatedCount(raw)" to "12000"),
            ),
            cursor = null)

    assertEquals(expected, result)
  }

  @Test
  fun `accepts raw values as search criteria`() {
    accessionsDao.update(
        accessionsDao.fetchOneById(AccessionId(1000))!!.copy(treesCollectedFrom = 8000))

    val rawPlantsField = rootPrefix.resolve("plantsCollectedFrom(raw)")
    val rawRareField = rootPrefix.resolve("species_rare(raw)")
    val rawStateField = rootPrefix.resolve("state(raw)")
    val rawTotalWeightField = rootPrefix.resolve("totalWithdrawnWeightGrams(raw)")

    val fields =
        listOf(
            accessionIdField,
            rawPlantsField,
            rawRareField,
            rawStateField,
            rawTotalWeightField,
        )

    val criteria =
        AndNode(
            listOf(
                FieldNode(rawPlantsField, listOf("8000")),
                FieldNode(rawRareField, listOf("false")),
                FieldNode(rawStateField, listOf(AccessionState.InStorage.displayName)),
                FieldNode(rawTotalWeightField, listOf("5000")),
            ))

    val result = Locales.GIBBERISH.use { searchService.search(rootPrefix, fields, criteria) }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "id" to "1000",
                    "plantsCollectedFrom(raw)" to "8000",
                    "species_rare(raw)" to "false",
                    "state(raw)" to "In Storage",
                    "totalWithdrawnWeightGrams(raw)" to "5000",
                ),
            ),
            cursor = null)

    assertEquals(expected, result)
  }
}
