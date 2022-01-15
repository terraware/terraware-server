package com.terraformation.backend.gis.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.GISAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.PlantNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.pojos.PlantsRow
import com.terraformation.backend.gis.db.FeatureStore
import com.terraformation.backend.gis.model.FeatureModel
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Instant
import java.time.LocalDate
import javax.ws.rs.BadRequestException
import javax.ws.rs.NotFoundException
import javax.ws.rs.QueryParam
import net.postgis.jdbc.geometry.Geometry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/gis/plants")
@RestController
@GISAppEndpoint
class PlantController(private val featureStore: FeatureStore) {
  private val log = perClassLogger()

  @ApiResponse(responseCode = "200", description = "The plant was created successfully.")
  @Operation(summary = "Creates a new plant. Use the Features API to delete the plant.")
  @PostMapping
  fun create(@RequestBody payload: CreatePlantRequestPayload): CreatePlantResponsePayload {
    try {
      val newPlant = featureStore.createPlant(payload.toRow())
      return CreatePlantResponsePayload(PlantResponse(newPlant))
    } catch (e: FeatureNotFoundException) {
      throw NotFoundException("Cannot create Plant. Feature id ${payload.featureId} is invalid.")
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404(description = "The specified plant doesn't exist.")
  @GetMapping("/{featureId}")
  fun get(@PathVariable featureId: FeatureId): GetPlantResponsePayload {
    val plant =
        featureStore.fetchFeature(featureId)?.plant
            ?: throw NotFoundException("The plant with id $featureId doesn't exist.")
    return GetPlantResponsePayload(PlantResponse(plant))
  }

  @ApiResponse(responseCode = "200")
  @Operation(
      summary =
          "Fetch a list of the plants in a layer. Can apply species, entered time, " +
              "and/or notes filters.")
  @GetMapping("/list/{layerId}")
  fun getPlantsList(
      @QueryParam("speciesName") speciesName: String? = null,
      @QueryParam("minEnteredTime") minEnteredTime: Instant? = null,
      @QueryParam("maxEnteredTime") maxEnteredTime: Instant? = null,
      @QueryParam("notes") notes: String? = null,
      @PathVariable layerId: LayerId
  ): ListPlantsResponsePayload {
    val plants =
        featureStore.listFeatures(
            layerId = layerId,
            speciesName = speciesName,
            minEnteredTime = minEnteredTime,
            maxEnteredTime = maxEnteredTime,
            notes = notes,
            plantsOnly = true)

    return ListPlantsResponsePayload(plants.map { ListPlantsResponseElement(it) })
  }

  @ApiResponse(responseCode = "200")
  @Operation(
      summary =
          "Fetch a count of how many plants of each species exist in a layer. " +
              "Can filter based on enteredTime. Plants that are not associated with any species " +
              "will be grouped together and keyed on the sentinel value of -1.")
  @GetMapping("/list/summary/{layerId}")
  fun getPlantSummary(
      @QueryParam("minEnteredTime") minEnteredTime: Instant? = null,
      @QueryParam("maxEnteredTime") maxEnteredTime: Instant? = null,
      @PathVariable layerId: LayerId
  ): PlantSummaryResponsePayload {
    val summary =
        featureStore.fetchPlantSummary(
            layerId, minEnteredTime = minEnteredTime, maxEnteredTime = maxEnteredTime)
    return PlantSummaryResponsePayload(summary)
  }

  @ApiResponse(responseCode = "200", description = "The plant was updated successfully.")
  @ApiResponse404(description = "The specified plant doesn't exist.")
  @Operation(summary = "Update an existing plant. Overwrites all fields.")
  @PutMapping("/{featureId}")
  fun update(
      @RequestBody payload: UpdatePlantRequestPayload,
      @PathVariable featureId: FeatureId
  ): UpdatePlantResponsePayload {
    try {
      val updatedPlant = featureStore.updatePlant(payload.toRow(featureId))
      return UpdatePlantResponsePayload(PlantResponse(updatedPlant))
    } catch (e: PlantNotFoundException) {
      throw NotFoundException("The plant with feature id $featureId doesn't exist.")
    }
  }

  // To delete a plant, the caller must delete the entire feature through the Features API

  @GetMapping("/summary")
  @Operation(
      summary =
          "Gets the number of plants and species in all the layers across a site, project, or " +
              "organization.",
      description =
          "Optionally also gets the plants and species counts as of a previous time, which " +
              "clients can use to compute the amount of change over time.")
  fun getPlantsAndSpeciesSummary(
      @RequestParam("organizationId")
      @Schema(
          description =
              "ID of organization to summarize. If not present, either projectId or siteId must " +
                  "be present. Ignored if projectId or siteId is present.")
      organizationId: OrganizationId? = null,
      @RequestParam("projectId")
      @Schema(
          description =
              "ID of project to summarize. If not present, either organizationId or siteId " +
                  "must be present. Ignored if siteId is present.")
      projectId: ProjectId? = null,
      @RequestParam("siteId")
      @Schema(
          description =
              "ID of site to summarize. If not present, either organizationId or projectId " +
                  "must be present.")
      siteId: SiteId? = null,
      @RequestParam("previousTime")
      @Schema(
          description =
              "If present, the response will also include data for plants entered at or before " +
                  "this time, in addition to the most recent data.",
          example = "2021-01-05T11:22:33Z")
      previousTime: Instant? = null,
  ): PlantsAndSpeciesSummaryResponsePayload {
    val currentStats =
        if (siteId != null) {
          featureStore.fetchPlantSummary(siteId)
        } else if (projectId != null) {
          featureStore.fetchPlantSummary(projectId)
        } else if (organizationId != null) {
          featureStore.fetchPlantSummary(organizationId)
        } else {
          throw BadRequestException("Must specify organizationId, projectId, or siteId")
        }

    val previousStats =
        if (previousTime != null) {
          if (siteId != null) {
            featureStore.fetchPlantSummary(siteId, maxEnteredTime = previousTime)
          } else if (projectId != null) {
            featureStore.fetchPlantSummary(projectId, maxEnteredTime = previousTime)
          } else if (organizationId != null) {
            featureStore.fetchPlantSummary(organizationId, maxEnteredTime = previousTime)
          } else {
            log.error("BUG! Should have caught missing parameter earlier in controller method")
            null
          }
        } else {
          null
        }

    return PlantsAndSpeciesSummaryResponsePayload(
        previousTime,
        plants =
            CurrentAndPreviousCounts(
                current = currentStats.values.sum(),
                previous = previousStats?.values?.sum(),
            ),
        species =
            CurrentAndPreviousCounts(
                current = currentStats.keys.filter { it.value > 0 }.size,
                previous = previousStats?.keys?.filter { it.value > 0 }?.size),
    )
  }
}

data class CreatePlantRequestPayload(
    val featureId: FeatureId,
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
) {
  fun toRow(): PlantsRow {
    return PlantsRow(
        featureId = featureId,
        label = label,
        speciesId = speciesId,
        naturalRegen = naturalRegen,
        datePlanted = datePlanted)
  }
}

data class UpdatePlantRequestPayload(
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
) {
  fun toRow(id: FeatureId): PlantsRow {
    return PlantsRow(
        featureId = id,
        label = label,
        speciesId = speciesId,
        naturalRegen = naturalRegen,
        datePlanted = datePlanted)
  }
}

data class PlantResponse(
    val featureId: FeatureId,
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
) {
  constructor(
      row: PlantsRow
  ) : this(row.featureId!!, row.label, row.speciesId, row.naturalRegen, row.datePlanted)
}

data class ListPlantsResponseElement(
    val featureId: FeatureId,
    val label: String? = null,
    val speciesId: SpeciesId? = null,
    val naturalRegen: Boolean? = null,
    val datePlanted: LocalDate? = null,
    val layerId: LayerId,
    val gpsHorizAccuracy: Double? = null,
    val gpsVertAccuracy: Double? = null,
    val attrib: String? = null,
    val notes: String? = null,
    val enteredTime: Instant? = null,
    val geom: Geometry? = null,
) {
  constructor(
      model: FeatureModel
  ) : this(
      model.id!!,
      model.plant?.label,
      model.plant?.speciesId,
      model.plant?.naturalRegen,
      model.plant?.datePlanted,
      model.layerId!!,
      model.gpsHorizAccuracy,
      model.gpsVertAccuracy,
      model.attrib,
      model.notes,
      model.enteredTime,
      model.geom)
}

data class CreatePlantResponsePayload(val plant: PlantResponse) : SuccessResponsePayload

data class GetPlantResponsePayload(val plant: PlantResponse) : SuccessResponsePayload

data class ListPlantsResponsePayload(val list: List<ListPlantsResponseElement>) :
    SuccessResponsePayload

data class PlantSummaryResponsePayload(val summary: Map<SpeciesId, Int>) : SuccessResponsePayload

data class UpdatePlantResponsePayload(val plant: PlantResponse) : SuccessResponsePayload

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Represents the current, and optionally a previous, count of a quantity such as the " +
            "number of plants or species.")
data class CurrentAndPreviousCounts(
    @Schema(
        description = "The current value of the quantity in question.",
    )
    val current: Int,
    @Schema(
        description =
            "The value of the quantity in question as of a previous point in time. Will not be " +
                "present if no previousTime value was specified in the request.")
    val previous: Int?
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PlantsAndSpeciesSummaryResponsePayload(
    val previousTime: Instant?,
    val plants: CurrentAndPreviousCounts,
    val species: CurrentAndPreviousCounts,
) : SuccessResponsePayload
