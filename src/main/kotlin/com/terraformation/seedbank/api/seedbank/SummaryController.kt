package com.terraformation.seedbank.api.seedbank

import com.terraformation.seedbank.api.annotation.SeedBankApp
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/seedbank/summary")
@SeedBankApp
class SummaryController {
  @GetMapping("/accessions")
  @Operation(
      summary = "Get accessions summary",
      description = "A summary of active accessions broken down by state.",
  )
  fun getAccessions(): AccessionSummaryResponse {
    return AccessionSummaryResponse(500, 550, 100, 100, 300, 280)
  }

  @GetMapping("/species")
  @Operation(
      summary = "Get species summary",
      description =
          "A summary of species including a list of the species with the most accessions.",
  )
  fun getSpecies(
      @Parameter(description = "The maximum number of per-species counts to include.")
      @Min(0)
      @Max(100L)
      maxCount: Int = 10
  ): SpeciesSummaryResponse {
    return SpeciesSummaryResponse(180, 150, mapOf("Species A" to 50))
  }

  @GetMapping("/updates")
  @Operation(
      summary = "Get accession state update summary",
      description = "A summary of the number of accessions in each of several states.",
  )
  fun getStateUpdates(): StateUpdateSummaryResponse {
    return StateUpdateSummaryResponse(100, 70, 50, 10)
  }
}

@Schema(description = "Summary of active accessions including the number currently in each state.")
data class AccessionSummaryResponse(
    @Schema(description = "Total number of active accessions.") val total: Int,
    // TODO: Get clarification on how this should be computed
    @Schema(description = "Number of active accessions as of last week.") val lastWeekTotal: Int,
    val processing: Int,
    val drying: Int,
    val storage: Int,
    val testing: Int
)

@Schema(
    description =
        "Summary of the number of species whose seeds are currently present in the seed bank.",
)
data class SpeciesSummaryResponse(
    @Schema(description = "Total number of distinct seed species in the seed bank.") val total: Int,
    @Schema(description = "How many species were in the seed bank last week.")
    // TODO: Get clarification on how this should be computed
    val lastWeekTotal: Int,
    @Schema(description = "Number of accessions for each of the most numerous species.")
    val accessionsPerSpecies: Map<String, Int>
)

data class StateUpdateSummaryResponse(
    @Schema(description = "Number of accessions currently waiting to be labeled and processed.")
    val droppedOff: Int,
    @Schema(
        description =
            "Number of accessions that have been processed and are ready to be dried. This does not " +
                "include accessions that have been processed but are not yet ready to be dried.",
    )
    val processed: Int,
    @Schema(
        description =
            "Number of accessions that have been dried and are ready to be stored. This does not " +
                "include accessions that have been dried but are not yet ready to be stored.",
    )
    val dried: Int,
    @Schema(description = "Number of accessions that have been completely withdrawn recently.")
    // TODO: Get clarification on what the time cutoff should be
    val withdrawn: Int
)
