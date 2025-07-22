package com.terraformation.backend.seedbank.search

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
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(estSeedCount = 12000))

    val fields =
        listOf(rootPrefix.resolve("estimatedCount"), rootPrefix.resolve("estimatedCount(raw)"))
    val criteria = FieldNode(accessionIdField, listOf("$accessionId1"))

    val result = searchService.search(rootPrefix, fields, mapOf(rootPrefix to criteria))

    val expected =
        SearchResults(listOf(mapOf("estimatedCount" to "12,000", "estimatedCount(raw)" to "12000")))

    assertEquals(expected, result)
  }

  @Test
  fun `accepts raw values as search criteria`() {
    accessionsDao.update(accessionsDao.fetchOneById(accessionId1)!!.copy(treesCollectedFrom = 8000))

    val rawActiveField = rootPrefix.resolve("active(raw)")
    val rawPlantsField = rootPrefix.resolve("plantsCollectedFrom(raw)")
    val rawRareField = rootPrefix.resolve("species_rare(raw)")
    val rawStateField = rootPrefix.resolve("state(raw)")
    val rawTotalWeightField = rootPrefix.resolve("totalWithdrawnWeightGrams(raw)")

    val fields =
        listOf(
            accessionIdField,
            rawActiveField,
            rawPlantsField,
            rawRareField,
            rawStateField,
            rawTotalWeightField,
        )

    val criteria =
        AndNode(
            listOf(
                FieldNode(rawActiveField, listOf("Active")),
                FieldNode(rawPlantsField, listOf("8000")),
                FieldNode(rawRareField, listOf("false")),
                FieldNode(rawStateField, listOf(AccessionState.InStorage.jsonValue)),
                FieldNode(rawTotalWeightField, listOf("5000")),
            ))

    val result =
        Locales.GIBBERISH.use {
          searchService.search(rootPrefix, fields, mapOf(rootPrefix to criteria))
        }

    val expected =
        SearchResults(
            listOf(
                mapOf(
                    "active(raw)" to "Active",
                    "id" to "$accessionId1",
                    "plantsCollectedFrom(raw)" to "8000",
                    "species_rare(raw)" to "false",
                    "state(raw)" to "In Storage",
                    "totalWithdrawnWeightGrams(raw)" to "5000",
                )))

    assertEquals(expected, result)
  }
}
