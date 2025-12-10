package com.terraformation.backend.tracking.api

import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservableCondition
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationMediaType
import com.terraformation.backend.db.tracking.ObservationPlotPosition
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.file.api.MediaKind
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotMediaModel
import com.terraformation.backend.tracking.model.ObservationMonitoringPlotResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingSubzoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneResultsModel
import com.terraformation.backend.tracking.model.ObservationPlantingZoneRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationResultsModel
import com.terraformation.backend.tracking.model.ObservationRollupResultsModel
import com.terraformation.backend.tracking.model.ObservationSpeciesResultsModel
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon

@Schema(description = "Information about observed plants of a particular species in a region.")
data class ObservationSpeciesResultsPayload(
    val certainty: RecordedSpeciesCertainty,
    @Schema(
        description =
            "Number of dead plants observed in permanent monitoring plots in all observations " +
                "including this one. 0 if this is a plot-level result for a temporary monitoring " +
                "plot."
    )
    val cumulativeDead: Int,
    @Schema(
        description =
            "Percentage of plants in permanent monitoring plots that are dead. If there are no " +
                "permanent monitoring plots (or if this is a plot-level result for a temporary " +
                "monitoring plot) this will be null."
    )
    val mortalityRate: Int?,
    @Schema(
        description =
            "Number of live plants observed in permanent plots in this observation, not " +
                "including existing plants. 0 if ths is a plot-level result for a temporary " +
                "monitoring plot."
    )
    val permanentLive: Int,
    @Schema(
        description =
            "If certainty is Known, the ID of the species. Null if certainty is Other or Unknown."
    )
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "If certainty is Other, the user-supplied name of the species. Null if certainty is " +
                "Known or Unknown."
    )
    val speciesName: String?,
    @Schema(
        description =
            "Percentage of plants in permanent monitoring plots that have survived since the t0 " +
                "point. If there are no permanent monitoring plots (or if this is a plot-level " +
                "result for a temporary monitoring plot) this will be null."
    )
    val survivalRate: Int?,
    @Schema(description = "Number of dead plants observed in this observation.") //
    val totalDead: Int,
    @Schema(description = "Number of existing plants observed in this observation.")
    val totalExisting: Int,
    @Schema(
        description =
            "Number of live plants observed in this observation, not including existing plants."
    )
    val totalLive: Int,
    @Schema(description = "Total number of live and existing plants of this species.")
    val totalPlants: Int,
) {
  constructor(
      model: ObservationSpeciesResultsModel
  ) : this(
      certainty = model.certainty,
      cumulativeDead = model.cumulativeDead,
      mortalityRate = model.mortalityRate,
      permanentLive = model.permanentLive,
      speciesId = model.speciesId,
      speciesName = model.speciesName,
      survivalRate = model.survivalRate,
      totalDead = model.totalDead,
      totalExisting = model.totalExisting,
      totalLive = model.totalLive,
      totalPlants = model.totalPlants,
  )
}

data class ObservationMonitoringPlotMediaPayload(
    val caption: String?,
    val fileId: FileId,
    val gpsCoordinates: Point?,
    @Schema(
        description =
            "If true, this file was uploaded as part of the original observation. If false, it " +
                "was uploaded later."
    )
    val isOriginal: Boolean,
    val mediaKind: MediaKind,
    val position: ObservationPlotPosition?,
    val type: ObservationMediaType,
) {
  constructor(
      model: ObservationMonitoringPlotMediaModel
  ) : this(
      model.caption,
      model.fileId,
      model.gpsCoordinates,
      model.isOriginal,
      MediaKind.forMimeType(model.contentType),
      model.position,
      model.type,
  )
}

data class ObservationMonitoringPlotResultsPayload(
    val boundary: Polygon,
    val claimedByName: String?,
    val claimedByUserId: UserId?,
    val completedTime: Instant?,
    val conditions: Set<ObservableCondition>,
    @ArraySchema(
        arraySchema = Schema(description = "Observed coordinates, if any, up to one per position.")
    )
    val coordinates: List<ObservationMonitoringPlotCoordinatesPayload>,
    val elevationMeters: BigDecimal?,
    val isAdHoc: Boolean,
    @Schema(
        description =
            "True if this was a permanent monitoring plot in this observation. Clients should " +
                "not assume that the set of permanent monitoring plots is the same in all " +
                "observations; the number of permanent monitoring plots can be adjusted over " +
                "time based on observation results."
    )
    val isPermanent: Boolean,
    val media: List<ObservationMonitoringPlotMediaPayload>,
    val monitoringPlotId: MonitoringPlotId,
    @Schema(description = "Full name of this monitoring plot, including zone and subzone prefixes.")
    val monitoringPlotName: String,
    @Schema(description = "Organization-unique number of this monitoring plot.")
    val monitoringPlotNumber: Long,
    @Schema(
        description =
            "If this is a permanent monitoring plot in this observation, percentage of plants of " +
                "all species that were dead."
    )
    val mortalityRate: Int?,
    val notes: String?,
    @Schema(description = "IDs of any newer monitoring plots that overlap with this one.")
    val overlappedByPlotIds: Set<MonitoringPlotId>,
    @Schema(description = "IDs of any older monitoring plots this one overlaps with.")
    val overlapsWithPlotIds: Set<MonitoringPlotId>,
    @Schema(description = "Number of live plants per hectare.") //
    val plantingDensity: Int,
    val plants: List<RecordedPlantPayload>?,
    @Schema(description = "Length of each edge of the monitoring plot in meters.")
    val sizeMeters: Int,
    val species: List<ObservationSpeciesResultsPayload>,
    val status: ObservationPlotStatus,
    @Schema(
        description =
            "If this is a permanent monitoring plot in this observation, percentage of plants that " +
                "have survived since t0 data."
    )
    val survivalRate: Int?,
    @Schema(
        description =
            "Total number of plants recorded. Includes all plants, regardless of live/dead " +
                "status or species."
    )
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total."
    )
    val totalSpecies: Int,
    @Schema(description = "Information about plants of unknown species, if any were observed.")
    val unknownSpecies: ObservationSpeciesResultsPayload?,
) {
  constructor(
      model: ObservationMonitoringPlotResultsModel
  ) : this(
      boundary = model.boundary,
      claimedByName = model.claimedByName,
      claimedByUserId = model.claimedByUserId,
      completedTime = model.completedTime,
      conditions = model.conditions,
      coordinates = model.coordinates.map { ObservationMonitoringPlotCoordinatesPayload(it) },
      elevationMeters = model.elevationMeters,
      isAdHoc = model.isAdHoc,
      isPermanent = model.isPermanent,
      media = model.media.map { ObservationMonitoringPlotMediaPayload(it) },
      monitoringPlotId = model.monitoringPlotId,
      monitoringPlotName = "${model.monitoringPlotNumber}",
      monitoringPlotNumber = model.monitoringPlotNumber,
      mortalityRate = model.mortalityRate,
      notes = model.notes,
      overlappedByPlotIds = model.overlappedByPlotIds,
      overlapsWithPlotIds = model.overlapsWithPlotIds,
      plantingDensity = model.plantingDensity,
      plants = model.plants?.map { RecordedPlantPayload(it) },
      sizeMeters = model.sizeMeters,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      status = model.status,
      survivalRate = model.survivalRate,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
      unknownSpecies =
          model.species
              .firstOrNull { it.certainty == RecordedSpeciesCertainty.Unknown }
              ?.let { ObservationSpeciesResultsPayload(it) },
  )

  // TODO: Remove this once frontend is updated to read the "media" property
  val photos: List<ObservationMonitoringPlotMediaPayload>
    get() = media
}

@Schema(description = "Use ObservationSubstratumResultsPayload instead", deprecated = true)
data class ObservationPlantingSubzoneResultsPayload(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    val estimatedPlants: Int?,
    val monitoringPlots: List<ObservationMonitoringPlotResultsPayload>,
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    val name: String,
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    val plantingSubzoneId: PlantingSubzoneId?,
    val species: List<ObservationSpeciesResultsPayload>,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationPlantingSubzoneResultsModel
  ) : this(
      areaHa = model.areaHa,
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      monitoringPlots = model.monitoringPlots.map { ObservationMonitoringPlotResultsPayload(it) },
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      name = model.name,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSubzoneId = model.plantingSubzoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class ObservationSubstratumResultsPayload(
    @Schema(description = "Area of this planting subzone in hectares.") //
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    @Schema(
        description =
            "Estimated number of plants in planting subzone based on estimated planting density " +
                "and subzone area. Only present if the subzone has completed planting."
    )
    val estimatedPlants: Int?,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this subzone's permanent " +
                "monitoring plots."
    )
    val monitoringPlots: List<ObservationMonitoringPlotResultsPayload>,
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    val name: String,
    @Schema(
        description =
            "Estimated planting density for the subzone based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    @Schema(
        description = "ID of the subzone. Absent if the subzone was deleted after the observation."
    )
    val plantingSubzoneId: SubstratumId?,
    val species: List<ObservationSpeciesResultsPayload>,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    @Schema(
        description =
            "Total number of plants recorded. Includes all plants, regardless of live/dead " +
                "status or species."
    )
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total."
    )
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationPlantingSubzoneResultsModel
  ) : this(
      areaHa = model.areaHa,
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      monitoringPlots = model.monitoringPlots.map { ObservationMonitoringPlotResultsPayload(it) },
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      name = model.name,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSubzoneId = model.plantingSubzoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

@Schema(description = "Use ObservationStratumResultsPayload instead", deprecated = true)
data class ObservationPlantingZoneResultsPayload(
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    val estimatedPlants: Int?,
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    val name: String,
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    val plantingSubzones: List<ObservationPlantingSubzoneResultsPayload>,
    val plantingZoneId: PlantingZoneId?,
    val species: List<ObservationSpeciesResultsPayload>,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    val totalPlants: Int,
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationPlantingZoneResultsModel
  ) : this(
      areaHa = model.areaHa,
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      name = model.name,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSubzones =
          model.plantingSubzones.map { ObservationPlantingSubzoneResultsPayload(it) },
      plantingZoneId = model.plantingZoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class ObservationStratumResultsPayload(
    @Schema(description = "Area of this planting zone in hectares.") //
    val areaHa: BigDecimal,
    val completedTime: Instant?,
    @Schema(
        description =
            "Estimated number of plants in planting zone based on estimated planting density and " +
                "planting zone area. Only present if all the subzones in the zone have been " +
                "marked as having completed planting."
    )
    val estimatedPlants: Int?,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this zone's permanent " +
                "monitoring plots."
    )
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    val name: String,
    @Schema(
        description =
            "Estimated planting density for the zone based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    @Schema(description = "ID of the zone. Absent if the zone was deleted after the observation.")
    val plantingZoneId: StratumId?,
    val species: List<ObservationSpeciesResultsPayload>,
    @Schema(
        description =
            "Percentage of plants of all species in this zone's permanent monitoring plots that " +
                "have survived since the t0 point."
    )
    val substrata: List<ObservationSubstratumResultsPayload>,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    @Schema(
        description =
            "Total number of plants recorded. Includes all plants, regardless of live/dead " +
                "status or species."
    )
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total."
    )
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationPlantingZoneResultsModel
  ) : this(
      areaHa = model.areaHa,
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      name = model.name,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingZoneId = model.plantingZoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      substrata = model.plantingSubzones.map { ObservationSubstratumResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class ObservationResultsPayload(
    val adHocPlot: ObservationMonitoringPlotResultsPayload?,
    val areaHa: BigDecimal?,
    val biomassMeasurements: ExistingBiomassMeasurementPayload?,
    val completedTime: Instant?,
    @Schema(
        description =
            "Estimated total number of live plants at the site, based on the estimated planting " +
                "density and site size. Only present if all the subzones in the site have been " +
                "marked as having completed planting."
    )
    val estimatedPlants: Int?,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this site's permanent " +
                "monitoring plots."
    )
    val isAdHoc: Boolean,
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    val observationId: ObservationId,
    @Schema(
        description =
            "Estimated planting density for the site, based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    val plantingSiteHistoryId: PlantingSiteHistoryId?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<ObservationPlantingZoneResultsPayload>,
    val species: List<ObservationSpeciesResultsPayload>,
    val startDate: LocalDate,
    val state: ObservationState,
    val strata: List<ObservationStratumResultsPayload>,
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    val totalPlants: Int,
    val totalSpecies: Int,
    val type: ObservationType,
) {
  constructor(
      model: ObservationResultsModel
  ) : this(
      adHocPlot = model.adHocPlot?.let { ObservationMonitoringPlotResultsPayload(it) },
      areaHa = model.areaHa,
      biomassMeasurements = model.biomassDetails?.let { ExistingBiomassMeasurementPayload.of(it) },
      completedTime = model.completedTime,
      estimatedPlants = model.estimatedPlants,
      isAdHoc = model.isAdHoc,
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      observationId = model.observationId,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSiteHistoryId = model.plantingSiteHistoryId,
      plantingSiteId = model.plantingSiteId,
      plantingZones = model.plantingZones.map { ObservationPlantingZoneResultsPayload(it) },
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      startDate = model.startDate,
      state = model.state,
      strata = model.plantingZones.map { ObservationStratumResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
      type = model.observationType,
  )
}

data class PlantingZoneObservationSummaryPayload(
    @Schema(description = "Area of this planting zone in hectares.") //
    val areaHa: BigDecimal,
    @Schema(description = "The earliest time of the observations used in this summary.")
    val earliestObservationTime: Instant,
    @Schema(
        description =
            "Estimated number of plants in planting zone based on estimated planting density and " +
                "planting zone area. Only present if all the subzones in the zone have been " +
                "marked as having completed planting."
    )
    val estimatedPlants: Int?,
    @Schema(description = "The latest time of the observations used in this summary.")
    val latestObservationTime: Instant,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this zone's permanent " +
                "monitoring plots."
    )
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    @Schema(
        description =
            "Estimated planting density for the zone based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    @Schema(description = "Use substrata instead", deprecated = true)
    val plantingSubzones: List<ObservationPlantingSubzoneResultsPayload>,
    val plantingZoneId: StratumId,
    @Schema(
        description =
            "Combined list of observed species and their statuses from the latest observation of each subzone."
    )
    val species: List<ObservationSpeciesResultsPayload>,
    @Schema(description = "List of subzone observations used in this summary.")
    val substrata: List<ObservationSubstratumResultsPayload>,
    @Schema(
        description =
            "Percentage of plants of all species in this zone's permanent monitoring plots that " +
                "have survived since the t0 point."
    )
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    @Schema(
        description =
            "Total number of plants recorded from the latest observations of each subzone. Includes all plants, regardless of live/dead status or species."
    )
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total."
    )
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationPlantingZoneRollupResultsModel
  ) : this(
      areaHa = model.areaHa,
      earliestObservationTime = model.earliestCompletedTime,
      estimatedPlants = model.estimatedPlants,
      latestObservationTime = model.latestCompletedTime,
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSubzones =
          model.plantingSubzones.map { ObservationPlantingSubzoneResultsPayload(it) },
      plantingZoneId = model.plantingZoneId,
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      substrata = model.plantingSubzones.map { ObservationSubstratumResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}

data class PlantingSiteObservationSummaryPayload(
    @Schema(description = "The earliest time of the observations used in this summary.")
    val earliestObservationTime: Instant,
    @Schema(
        description =
            "Estimated total number of live plants at the site, based on the estimated planting " +
                "density and site size. Only present if all the subzones in the site have been " +
                "marked as having completed planting."
    )
    val estimatedPlants: Int?,
    @Schema(description = "The latest time of the observations used in this summary.")
    val latestObservationTime: Instant,
    @Schema(
        description =
            "Percentage of plants of all species that were dead in this site's permanent " +
                "monitoring plots."
    )
    val mortalityRate: Int?,
    val mortalityRateStdDev: Int?,
    @Schema(
        description =
            "Estimated planting density for the site, based on the observed planting densities " +
                "of monitoring plots."
    )
    val plantingDensity: Int,
    val plantingDensityStdDev: Int?,
    val plantingSiteId: PlantingSiteId,
    val plantingZones: List<PlantingZoneObservationSummaryPayload>,
    @Schema(
        description =
            "Combined list of observed species and their statuses from the latest observation of each subzone within each zone."
    )
    val species: List<ObservationSpeciesResultsPayload>,
    @Schema(
        description =
            "Percentage of plants of all species in this site's permanent monitoring plots that " +
                "have survived since the t0 point."
    )
    val survivalRate: Int?,
    val survivalRateStdDev: Int?,
    @Schema(
        description =
            "Total number of plants recorded from the latest observations of each subzone within each zone. Includes all plants, regardless of live/dead status or species."
    )
    val totalPlants: Int,
    @Schema(
        description =
            "Total number of species observed, not counting dead plants. Includes plants with " +
                "Known and Other certainties. In the case of Other, each distinct user-supplied " +
                "species name is counted as a separate species for purposes of this total."
    )
    val totalSpecies: Int,
) {
  constructor(
      model: ObservationRollupResultsModel
  ) : this(
      earliestObservationTime = model.earliestCompletedTime,
      estimatedPlants = model.estimatedPlants,
      latestObservationTime = model.latestCompletedTime,
      mortalityRate = model.mortalityRate,
      mortalityRateStdDev = model.mortalityRateStdDev,
      plantingDensity = model.plantingDensity,
      plantingDensityStdDev = model.plantingDensityStdDev,
      plantingSiteId = model.plantingSiteId,
      plantingZones = model.plantingZones.map { PlantingZoneObservationSummaryPayload(it) },
      species =
          model.species
              .filter { it.certainty != RecordedSpeciesCertainty.Unknown }
              .map { ObservationSpeciesResultsPayload(it) },
      survivalRate = model.survivalRate,
      survivalRateStdDev = model.survivalRateStdDev,
      totalPlants = model.totalPlants,
      totalSpecies = model.totalSpecies,
  )
}
