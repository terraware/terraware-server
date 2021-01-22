package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/seedbank/summary")
@SeedBankAppEndpoint
class SummaryController {
  @GetMapping
  @Operation(summary = "Get summary statistics about the seed bank")
  fun getSummary(): SummaryResponse {
    return SummaryResponse(
        activeAccessions = SummaryStatistic(500, 550),
        species = SummaryStatistic(180, 150),
        families = SummaryStatistic(95, 90),
        overduePendingAccessions = 100,
        overdueProcessedAccessions = 70,
        overdueDriedAccessions = 50,
        recentlyWithdrawnAccessions = 10)
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
