package com.terraformation.backend.seedbank.search

import com.terraformation.backend.db.default_schema.WithdrawalCustomPurposeType
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.search.FieldNode
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchFilterType
import com.terraformation.backend.search.SearchResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

internal class SearchServiceWithdrawalTest : SearchServiceTest() {
  @Test
  fun `withdrawals expose custom purposes`() {
    val salePurposeId =
        insertWithdrawalCustomPurpose(
            name = "Sále",
            type = WithdrawalCustomPurposeType.SeedBank,
        )
    val giftPurposeId =
        insertWithdrawalCustomPurpose(
            name = "Gift",
            type = WithdrawalCustomPurposeType.SeedBank,
        )
    val saleWithdrawalId =
        insertSeedbankWithdrawal(
            accessionId = accessionId1,
            batchId = null,
            customPurposeId = salePurposeId,
            purpose = WithdrawalPurpose.Other,
        )
    insertSeedbankWithdrawal(
        accessionId = accessionId1,
        batchId = null,
        customPurposeId = giftPurposeId,
        purpose = WithdrawalPurpose.Other,
    )
    insertSeedbankWithdrawal(
        accessionId = accessionId1,
        batchId = null,
        purpose = WithdrawalPurpose.Other,
    )

    val prefix = SearchFieldPrefix(root = tables.withdrawals)
    val fields = listOf("id", "customPurpose").map { prefix.resolve(it) }

    val saleResult =
        SearchResults(listOf(mapOf("id" to "$saleWithdrawalId", "customPurpose" to "Sále")))

    assertAll(
        *listOf(
                FieldNode(
                    prefix.resolve("customPurpose"),
                    listOf("SALE"),
                    SearchFilterType.Exact,
                ),
                FieldNode(
                    prefix.resolve("customPurpose"),
                    listOf("sale"),
                    SearchFilterType.Fuzzy,
                ),
                FieldNode(
                    prefix.resolve("customPurpose"),
                    listOf("S"),
                    SearchFilterType.Fuzzy,
                ),
            )
            .map { filter ->
              {
                assertEquals(
                    saleResult,
                    searchService.search(prefix, fields, mapOf(prefix to filter)),
                    "Custom purpose filter $filter",
                )
              }
            }
            .toTypedArray(),
    )
  }
}
