package com.terraformation.backend.seedbank.api

import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.time.atMostRecent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.springframework.web.bind.annotation.GetMapping
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
    private val parentStore: ParentStore,
    private val speciesStore: SpeciesStore,
) {
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

  private fun getSummary(facilityId: FacilityId): SummaryResponse {
    val now = ZonedDateTime.now(clock)
    val startOfDay = now.atMostRecent(config.dailyTasks.startTime)
    val startOfWeek = startOfDay.atMostRecent(DayOfWeek.MONDAY)

    // For purposes of scanning for overdue accessions, "One week ago" is 6 days before the most
    // recent start of day because we need to include accessions that happened after start-of-day on
    // the same day a week earlier. Spec says if it is Monday morning, the count of week-old pending
    // accessions should include ones that arrived the previous Monday afternoon, so we need to use
    // start-of-day on the previous Tuesday (6 days earlier) as the cutoff.
    val oneWeekAgo = startOfDay.minusDays(6)
    val twoWeeksAgo = startOfDay.minusDays(13)

    val organizationId =
        parentStore.getOrganizationId(facilityId) ?: throw FacilityNotFoundException(facilityId)

    return SummaryResponse(
        activeAccessions = accessionStore.countActive(facilityId),
        species = speciesStore.countSpecies(organizationId),
        overduePendingAccessions =
            accessionStore.countInState(
                facilityId, AccessionState.Pending, sinceBefore = oneWeekAgo),
        overdueProcessedAccessions =
            accessionStore.countInState(
                facilityId, AccessionState.Processed, sinceBefore = twoWeeksAgo),
        overdueDriedAccessions = accessionStore.countInState(facilityId, AccessionState.Dried),
        recentlyWithdrawnAccessions =
            accessionStore.countInState(
                facilityId, AccessionState.Withdrawn, sinceAfter = startOfWeek),
        accessionsByState = accessionStore.countByState(facilityId),
        seedsRemaining =
            SeedCountSummaryPayload(
                accessionStore.countSeedsRemaining(facilityId),
                accessionStore.estimateSeedsRemainingByWeight(facilityId),
                accessionStore.countQuantityUnknown(facilityId)),
    )
  }

  private fun getSummary(organizationId: OrganizationId): SummaryResponse {
    val now = ZonedDateTime.now(clock)
    val startOfDay = now.atMostRecent(config.dailyTasks.startTime)
    val startOfWeek = startOfDay.atMostRecent(DayOfWeek.MONDAY)

    // For purposes of scanning for overdue accessions, "One week ago" is 6 days before the most
    // recent start of day because we need to include accessions that happened after start-of-day on
    // the same day a week earlier. Spec says if it is Monday morning, the count of week-old pending
    // accessions should include ones that arrived the previous Monday afternoon, so we need to use
    // start-of-day on the previous Tuesday (6 days earlier) as the cutoff.
    val oneWeekAgo = startOfDay.minusDays(6)
    val twoWeeksAgo = startOfDay.minusDays(13)

    return SummaryResponse(
        activeAccessions = accessionStore.countActive(organizationId),
        species = speciesStore.countSpecies(organizationId),
        overduePendingAccessions =
            accessionStore.countInState(
                organizationId, AccessionState.Pending, sinceBefore = oneWeekAgo),
        overdueProcessedAccessions =
            accessionStore.countInState(
                organizationId, AccessionState.Processed, sinceBefore = twoWeeksAgo),
        overdueDriedAccessions = accessionStore.countInState(organizationId, AccessionState.Dried),
        recentlyWithdrawnAccessions =
            accessionStore.countInState(
                organizationId, AccessionState.Withdrawn, sinceAfter = startOfWeek),
        accessionsByState = accessionStore.countByState(organizationId),
        seedsRemaining =
            SeedCountSummaryPayload(
                accessionStore.countSeedsRemaining(organizationId),
                accessionStore.estimateSeedsRemainingByWeight(organizationId),
                accessionStore.countQuantityUnknown(organizationId)),
    )
  }
}

@Schema(description = "Summary of important statistics about the seed bank for the Summary page.")
data class SummaryResponse(
    val activeAccessions: Int,
    val species: Int,
    @Schema(description = "Number of accessions in Pending state overdue for processing.")
    val overduePendingAccessions: Int,
    @Schema(description = "Number of accessions in Processed state overdue for drying.")
    val overdueProcessedAccessions: Int,
    @Schema(description = "Number of accessions in Dried state overdue for storage.")
    val overdueDriedAccessions: Int,
    @Schema(description = "Number of accessions withdrawn so far this week.")
    val recentlyWithdrawnAccessions: Int,
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
      subtotalBySeedCount: Long,
      subtotalByWeightEstimate: Long,
      unknownQuantityAccessions: Int
  ) : this(
      subtotalBySeedCount + subtotalByWeightEstimate,
      subtotalBySeedCount,
      subtotalByWeightEstimate,
      unknownQuantityAccessions,
  )
}
