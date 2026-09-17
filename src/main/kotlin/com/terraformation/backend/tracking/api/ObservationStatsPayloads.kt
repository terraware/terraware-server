package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.model.ObservationSiteStatsModel
import com.terraformation.backend.tracking.model.ObservationStratumStatsModel
import com.terraformation.backend.tracking.model.ObservationSubstratumStatsModel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(
    description =
        "Statistics for a substratum from the most recent observation of it. The statistics and " +
            "the observation they came from are absent if the substratum hasn't been observed yet."
)
data class ObservationSubstratumStatsPayload(
    val completedTime: Instant?,
    @Schema(
        description =
            "Which observation these statistics came from. This can differ between substrata."
    )
    val observationId: ObservationId?,
    @Schema(
        description =
            "Estimated planting density for the substratum based on the observed planting " +
                "densities of monitoring plots."
    )
    val plantingDensity: Int?,
    val substratumId: SubstratumId,
    @Schema(
        description =
            "Percentage of plants of all species in this substratum's permanent monitoring plots " +
                "that have survived since the t0 point."
    )
    val survivalRate: Int?,
    @Schema(
        description = "Total number of plants recorded, regardless of live/dead status or species."
    )
    val totalPlants: Int?,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Each distinct " +
                "user-supplied name for a species of Other certainty counts separately."
    )
    val totalSpecies: Int?,
) {
  constructor(
      model: ObservationSubstratumStatsModel
  ) : this(
      completedTime = model.completedTime,
      observationId = model.observationId,
      plantingDensity = model.plantingDensity,
      substratumId = model.substratumId,
      survivalRate = model.survivalRate,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

@Schema(
    description =
        "Statistics for a stratum from the most recent observation of it. This can be a different " +
            "observation than the ones the stratum's substrata came from."
)
data class ObservationStratumStatsPayload(
    val completedTime: Instant?,
    @Schema(
        description =
            "Which observation these statistics came from. This can differ between strata."
    )
    val observationId: ObservationId?,
    @Schema(
        description =
            "Estimated planting density for the stratum based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int?,
    val stratumId: StratumId,
    val substrata: List<ObservationSubstratumStatsPayload>,
    @Schema(
        description =
            "Percentage of plants of all species in this stratum's permanent monitoring plots " +
                "that have survived since the t0 point."
    )
    val survivalRate: Int?,
    @Schema(
        description = "Total number of plants recorded, regardless of live/dead status or species."
    )
    val totalPlants: Int?,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Each distinct " +
                "user-supplied name for a species of Other certainty counts separately."
    )
    val totalSpecies: Int?,
) {
  constructor(
      model: ObservationStratumStatsModel
  ) : this(
      completedTime = model.completedTime,
      observationId = model.observationId,
      plantingDensity = model.plantingDensity,
      stratumId = model.stratumId,
      substrata = model.substrata.map { ObservationSubstratumStatsPayload(it) },
      survivalRate = model.survivalRate,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

@Schema(
    description =
        "Statistics for a planting site and each of its strata and substrata. Each area's " +
            "statistics come from the most recent observation of that area, so different areas " +
            "can come from different observations. Areas that haven't been observed yet are " +
            "included without statistics."
)
data class ObservationSiteStatsPayload(
    val completedTime: Instant?,
    @Schema(description = "Which observation these site-level statistics came from.")
    val observationId: ObservationId?,
    @Schema(
        description =
            "Estimated planting density for the site based on the observed planting densities of " +
                "monitoring plots."
    )
    val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val strata: List<ObservationStratumStatsPayload>,
    @Schema(
        description =
            "Percentage of plants of all species in the site's permanent monitoring plots that " +
                "have survived since the t0 point."
    )
    val survivalRate: Int?,
    @Schema(
        description = "Total number of plants recorded, regardless of live/dead status or species."
    )
    val totalPlants: Int?,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Each distinct " +
                "user-supplied name for a species of Other certainty counts separately."
    )
    val totalSpecies: Int?,
) {
  constructor(
      model: ObservationSiteStatsModel
  ) : this(
      completedTime = model.completedTime,
      observationId = model.observationId,
      plantingDensity = model.plantingDensity,
      plantingSiteId = model.plantingSiteId,
      strata = model.strata.map { ObservationStratumStatsPayload(it) },
      survivalRate = model.survivalRate,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}
