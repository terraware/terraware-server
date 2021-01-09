package com.terraformation.seedbank.api

import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces
import io.micronaut.http.annotation.QueryValue
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.*
import javax.inject.Singleton
import javax.validation.constraints.Max
import javax.validation.constraints.Min

@Controller("/api/v1/summary")
@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Tag(name = "SeedBankApp")
class SummaryController {
  /** Returns a summary of active accessions broken down by state. */
  @Get("/accessions")
  fun getAccessions(): AccessionSummary {
    return AccessionSummary(500, 550, 100, 100, 300, 280)
  }

  /** Returns a summary of species including a list of the species with the most accessions. */
  @Get("/species{?maxCount}")
  fun getSpecies(
      /** The maximum number of per-species counts to include. */
      @QueryValue(defaultValue = "10") @Min(0) @Max(100L) maxCount: Optional<Int>
  ): SpeciesSummary {
    return SpeciesSummary(180, 150, mapOf("Species A" to 50))
  }

  /** Returns a summary of the number of accessions in each of several states. */
  @Get("/updates")
  fun getStateUpdates(): StateUpdateSummary {
    return StateUpdateSummary(100, 70, 50, 10)
  }
}

/** Summary of active accessions including the number currently in each state. */
data class AccessionSummary(
    /** Total number of active accessions. */
    val total: Int,
    /**
     * Number of active accessions as of last week.
     *
     * TODO: Get clarification on how this should be computed
     */
    val lastWeekTotal: Int,
    val processing: Int,
    val drying: Int,
    val storage: Int,
    val testing: Int
)

/** Summary of the number of species whose seeds are currently present in the seed bank. */
data class SpeciesSummary(
    /** Total number of distinct seed species in the seed bank. */
    val total: Int,
    /**
     * How many species were in the seed bank last week.
     *
     * TODO: Get clarification on how this should be computed
     */
    val lastWeekTotal: Int,
    /** Number of accessions for each of the most numerous species. */
    val accessionsPerSpecies: Map<String, Int>
)

data class StateUpdateSummary(
    /** Number of accessions currently waiting to be labeled and processed. */
    val droppedOff: Int,
    /**
     * Number of accessions that have been processed and are ready to be dried. This does not
     * include accessions that have been processed but are not yet ready to be dried.
     */
    val processed: Int,
    /**
     * Number of accessions that have been dried and are ready to be stored. This does not include
     * accessions that have been dried but are not yet ready to be stored.
     */
    val dried: Int,
    /**
     * Number of accessions that have been completely withdrawn recently.
     *
     * TODO: Get clarification on what the time cutoff should be
     */
    val withdrawn: Int
)
