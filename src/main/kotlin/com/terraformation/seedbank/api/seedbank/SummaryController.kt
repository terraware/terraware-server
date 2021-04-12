package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.SpeciesStore
import com.terraformation.seedbank.services.atMostRecent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Clock
import java.time.DayOfWeek
import java.time.ZonedDateTime
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/seedbank/summary")
@SeedBankAppEndpoint
class SummaryController(
    private val accessionStore: AccessionStore,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val speciesStore: SpeciesStore,
) {
  @GetMapping
  @Operation(summary = "Get summary statistics about the seed bank")
  fun getSummary(): SummaryResponse {
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
        activeAccessions =
            SummaryStatistic(
                accessionStore.countActive(now), accessionStore.countActive(startOfWeek)),
        species =
            SummaryStatistic(
                speciesStore.countSpecies(now), speciesStore.countSpecies(startOfWeek)),
        families =
            SummaryStatistic(
                speciesStore.countFamilies(now), speciesStore.countFamilies(startOfWeek)),
        overduePendingAccessions =
            accessionStore.countInState(AccessionState.Pending, sinceBefore = oneWeekAgo),
        overdueProcessedAccessions =
            accessionStore.countInState(AccessionState.Processed, sinceBefore = twoWeeksAgo),
        overdueDriedAccessions = accessionStore.countInState(AccessionState.Dried),
        recentlyWithdrawnAccessions =
            accessionStore.countInState(AccessionState.Withdrawn, sinceAfter = startOfWeek))
  }
}

@Schema(description = "The current value and value as of last week for a summary statistic")
data class SummaryStatistic(val current: Int, val lastWeek: Int)

@Schema(description = "Summary of important statistics about the seed bank for the Summary page.")
data class SummaryResponse(
    val activeAccessions: SummaryStatistic,
    val species: SummaryStatistic,
    val families: SummaryStatistic,
    @Schema(description = "Number of accessions in Pending state overdue for processing")
    val overduePendingAccessions: Int,
    @Schema(description = "Number of accessions in Processed state overdue for drying")
    val overdueProcessedAccessions: Int,
    @Schema(description = "Number of accessions in Dried state overdue for storage")
    val overdueDriedAccessions: Int,
    @Schema(description = "Number of accessions withdrawn so far this week")
    val recentlyWithdrawnAccessions: Int,
)
