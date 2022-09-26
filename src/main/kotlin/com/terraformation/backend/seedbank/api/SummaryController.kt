package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.search.SearchFieldPrefix
import com.terraformation.backend.search.SearchService
import com.terraformation.backend.search.api.HasSearchNode
import com.terraformation.backend.search.api.SearchNodePayload
import com.terraformation.backend.search.table.SearchTables
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionSummaryStatistics
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Clock
import org.jooq.Record1
import org.jooq.Select
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/seedbank/summary")
@SeedBankAppEndpoint
class SummaryController(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val searchService: SearchService,
    tables: SearchTables,
) {
  private val accessionsPrefix = SearchFieldPrefix(tables.accessions)
  private val accessionIdField = accessionsPrefix.resolve("id")

  @GetMapping
  @Operation(
      summary =
          "Get summary statistics about a specific seed bank or all seed banks within an " +
              "organization.")
  fun getSeedBankSummary(
      @RequestParam("organizationId", required = false)
      @Schema(description = "If set, return summary on all seedbanks for that organization.")
      organizationId: OrganizationId?,
      @RequestParam("facilityId", required = false)
      @Schema(description = "If set, return summary on that specific seedbank.")
      facilityId: FacilityId?
  ): SummaryResponse {
    return when {
      facilityId != null && organizationId == null -> getSummary(facilityId)
      facilityId == null && organizationId != null -> getSummary(organizationId)
      else ->
          throw IllegalArgumentException("Must specify organization or facility ID but not both")
    }
  }

  @Operation(
      summary =
          "Get summary statistics about the accessions that match a specified set of " +
              "search criteria.")
  @PostMapping
  fun summarizeAccessionSearch(
      @RequestBody payload: SummarizeAccessionSearchRequestPayload
  ): SummarizeAccessionSearchResponsePayload {
    @Suppress("UNCHECKED_CAST")
    val query =
        searchService
            .buildQuery(
                accessionsPrefix, listOf(accessionIdField), payload.toSearchNode(accessionsPrefix))
            .toSelectQuery() as Select<Record1<AccessionId?>>

    val summary = accessionStore.getSummaryStatistics(query)

    return SummarizeAccessionSearchResponsePayload(
        summary.accessions, summary.species, SeedCountSummaryPayload(summary))
  }

  private fun getSummary(facilityId: FacilityId): SummaryResponse {
    val stats = accessionStore.getSummaryStatistics(facilityId)

    return SummaryResponse(
        activeAccessions = stats.accessions,
        species = stats.species,
        accessionsByState = accessionStore.countByState(facilityId),
        seedsRemaining = SeedCountSummaryPayload(stats),
    )
  }

  private fun getSummary(organizationId: OrganizationId): SummaryResponse {
    val stats = accessionStore.getSummaryStatistics(organizationId)

    return SummaryResponse(
        activeAccessions = stats.accessions,
        species = stats.species,
        accessionsByState = accessionStore.countByState(organizationId),
        seedsRemaining = SeedCountSummaryPayload(stats),
    )
  }
}

@Schema(description = "Summary of important statistics about the seed bank for the Summary page.")
data class SummaryResponse(
    val activeAccessions: Int,
    val species: Int,
    @Schema(description = "Number of accessions in each state.")
    val accessionsByState: Map<AccessionState, Int>,
    @Schema(description = "Summary of the number of seeds remaining across all active accessions.")
    val seedsRemaining: SeedCountSummaryPayload,
) : SuccessResponsePayload

data class SeedCountSummaryPayload(
    @Schema(
        description =
            "Total number of seeds remaining. The sum of subtotalBySeedCount and " +
                "subtotalByWeightEstimate.")
    val total: Long,
    @Schema(
        description =
            "Total number of seeds remaining in accessions whose quantities are measured in seeds.")
    val subtotalBySeedCount: Long,
    @Schema(
        description =
            "Estimated total number of seeds remaining in accessions whose quantities are " +
                "measured by weight. This estimate is based on the subset weight and count. " +
                "Accessions measured by weight that don't have subset weights and counts are not " +
                "included in this estimate.")
    val subtotalByWeightEstimate: Long,
    @Schema(
        description =
            "Number of accessions that are measured by weight and don't have subset weight and " +
                "count data. The system cannot estimate how many seeds they have.")
    val unknownQuantityAccessions: Int,
) {
  constructor(
      summary: AccessionSummaryStatistics
  ) : this(
      summary.totalSeedsRemaining,
      summary.subtotalBySeedCount,
      summary.subtotalByWeightEstimate,
      summary.unknownQuantityAccessions)
}

data class SummarizeAccessionSearchRequestPayload(
    override val search: SearchNodePayload? = null,
) : HasSearchNode

data class SummarizeAccessionSearchResponsePayload(
    val accessions: Int,
    val species: Int,
    val seedsRemaining: SeedCountSummaryPayload,
) : SuccessResponsePayload
