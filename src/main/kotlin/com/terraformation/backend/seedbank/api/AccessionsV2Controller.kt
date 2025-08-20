package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponse409
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.AccessionUpdateContext
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.util.orNull
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v2/seedbank/accessions")
@RestController
@SeedBankAppEndpoint
class AccessionsV2Controller(
    private val accessionStore: AccessionStore,
    private val facilityStore: FacilityStore,
) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number and ID.",
  )
  @Operation(summary = "Creates a new accession.")
  @PostMapping
  fun createAccession(
      @RequestBody payload: CreateAccessionRequestPayloadV2
  ): CreateAccessionResponsePayloadV2 {
    val clock = facilityStore.getClock(payload.facilityId)
    val updatedPayload = accessionStore.create(payload.toModel(clock))
    return CreateAccessionResponsePayloadV2(AccessionPayloadV2(updatedPayload))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.",
  )
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @ApiResponse409(
      description =
          "One of the requested changes couldn't be made because the accession is in a state " +
              "that doesn't allow the change."
  )
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{id}")
  fun updateAccession(
      @RequestBody payload: UpdateAccessionRequestPayloadV2,
      @PathVariable("id") accessionId: AccessionId,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved."
      )
      simulate: Boolean?,
  ): UpdateAccessionResponsePayloadV2 {
    val existing = accessionStore.fetchOneById(accessionId)
    val editedModel = payload.applyToModel(existing)
    val remainingQuantityNotes =
        if (editedModel.remaining != null) {
          payload.remainingQuantityNotes
        } else {
          null
        }
    val updateContext = AccessionUpdateContext(remainingQuantityNotes = remainingQuantityNotes)

    val updatedModel =
        if (simulate == true) {
          accessionStore.dryRun(editedModel)
        } else {
          accessionStore.updateAndFetch(editedModel, updateContext)
        }
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Retrieve an existing accession.")
  fun getAccession(@PathVariable("id") accessionId: AccessionId): GetAccessionResponsePayloadV2 {
    val accession = accessionStore.fetchOneById(accessionId)

    return GetAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }
}

/**
 * Supported accession states. This is a subset of the values in [AccessionState], minus obsolete
 * states that can still appear in accessions' state histories (and thus need to be kept around as
 * [AccessionState] enum values) but that can no longer be used as the current states for any
 * accessions.
 */
enum class AccessionStateV2(val modelState: AccessionState) {
  AwaitingCheckIn(AccessionState.AwaitingCheckIn),
  AwaitingProcessing(AccessionState.AwaitingProcessing),
  Processing(AccessionState.Processing),
  Drying(AccessionState.Drying),
  InStorage(AccessionState.InStorage),
  UsedUp(AccessionState.UsedUp);

  @get:JsonValue val jsonValue: String = modelState.jsonValue

  companion object {
    private val byModelState: Map<AccessionState, AccessionStateV2> by lazy {
      entries.associateBy { it.modelState }
    }

    private val byJsonValue: Map<String, AccessionStateV2> by lazy {
      entries.associateBy { it.jsonValue }
    }

    fun of(state: AccessionState): AccessionStateV2 =
        byModelState[state] ?: throw IllegalArgumentException("$state is no longer supported")

    @JsonCreator
    @JvmStatic
    fun forJsonValue(value: String): AccessionStateV2 =
        byJsonValue[value] ?: throw IllegalArgumentException("Unknown state $value")

    fun isValid(state: AccessionState): Boolean = state in byModelState
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class AccessionPayloadV2(
    @Schema(
        description =
            "Server-generated human-readable identifier for the accession. This is unique " +
                "within a single seed bank, but different seed banks may have accessions with " +
                "the same number."
    )
    val accessionNumber: String,
    @Schema(
        description = "Server-calculated active indicator. This is based on the accession's state."
    )
    val active: AccessionActive,
    val bagNumbers: Set<String>?,
    val collectedDate: LocalDate?,
    val collectionSiteCity: String? = null,
    val collectionSiteCoordinates: Set<Geolocation>?,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    @ArraySchema(arraySchema = Schema(description = "Names of the people who collected the seeds."))
    val collectors: List<String>?,
    val dryingEndDate: LocalDate?,
    @Schema(
        description =
            "Estimated number of seeds remaining. Absent if there isn't enough " +
                "information to calculate an estimate."
    )
    val estimatedCount: Int?,
    @Schema(
        description =
            "Estimated weight of seeds remaining. Absent if there isn't enough " +
                "information to calculate an estimate."
    )
    val estimatedWeight: SeedQuantityPayload?,
    val facilityId: FacilityId,
    @Schema(
        description =
            "If true, plants from this accession's seeds were delivered to a planting site."
    )
    val hasDeliveries: Boolean,
    @Schema(
        description =
            "Server-generated unique identifier for the accession. This is unique across all " +
                "seed banks, but is not suitable for display to end users."
    )
    val id: AccessionId,
    @Schema(
        description =
            "Most recent user observation of seeds remaining in the accession. This is not " +
                "directly editable; it is updated by the server whenever the " +
                "\"remainingQuantity\" field is edited."
    )
    val latestObservedQuantity: SeedQuantityPayload?,
    @Schema(
        description =
            "Time of most recent user observation of seeds remaining in the accession. This is " +
                "updated by the server whenever the \"remainingQuantity\" field is edited."
    )
    val latestObservedTime: Instant?,
    val notes: String?,
    val photoFilenames: List<String>?,
    val plantId: String?,
    @Schema(description = "Estimated number of plants the seeds were collected from.")
    val plantsCollectedFrom: Int?,
    val projectId: ProjectId?,
    val receivedDate: LocalDate?,
    @Schema(
        description =
            "Number or weight of seeds remaining for withdrawal and testing. May be calculated " +
                "by the server after withdrawals."
    )
    val remainingQuantity: SeedQuantityPayload?,
    @Schema(description = "Which source of data this accession originally came from.")
    val source: DataSource?,
    @Schema(
        description = "Scientific name of the species.",
    )
    val speciesScientificName: String?,
    @Schema(
        description = "Common name of the species, if defined.",
    )
    val speciesCommonName: String?,
    @Schema(
        description = "Server-generated unique ID of the species.",
    )
    val speciesId: SpeciesId?,
    val state: AccessionStateV2,
    val subLocation: String?,
    val subsetCount: Int?,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\"."
    )
    val subsetWeight: SeedQuantityPayload?,
    @Schema(
        description =
            "Total number of seeds withdrawn. If withdrawals are measured by weight, this is an " +
                "estimate based on the accession's subset count and weight."
    )
    val totalWithdrawnCount: Int?,
    @Schema(
        description =
            "Total weight of seeds withdrawn. If withdrawals are measured by seed " +
                "count, this is an estimate based on the accession's subset count and weight."
    )
    val totalWithdrawnWeight: SeedQuantityPayload?,
    val viabilityPercent: Int?,
    val viabilityTests: List<GetViabilityTestPayload>?,
    val withdrawals: List<GetWithdrawalPayload>?,
) {
  constructor(
      model: AccessionModel
  ) : this(
      accessionNumber =
          model.accessionNumber
              ?: throw IllegalArgumentException("Accession did not have a number"),
      active = model.active,
      bagNumbers = model.bagNumbers.orNull(),
      collectedDate = model.collectedDate,
      collectionSiteCity = model.collectionSiteCity,
      collectionSiteCoordinates = model.geolocations.orNull(),
      collectionSiteCountryCode = model.collectionSiteCountryCode,
      collectionSiteCountrySubdivision = model.collectionSiteCountrySubdivision,
      collectionSiteLandowner = model.collectionSiteLandowner,
      collectionSiteName = model.collectionSiteName,
      collectionSiteNotes = model.collectionSiteNotes,
      collectionSource = model.collectionSource,
      collectors = model.collectors.orNull(),
      dryingEndDate = model.dryingEndDate,
      estimatedCount = model.estimatedSeedCount,
      estimatedWeight = model.estimatedWeight?.toPayload(),
      facilityId =
          model.facilityId
              ?: throw IllegalArgumentException("Accession did not have a facility ID"),
      plantId = model.founderId,
      hasDeliveries = model.hasDeliveries,
      id = model.id ?: throw IllegalArgumentException("Accession did not have an ID"),
      latestObservedQuantity = model.latestObservedQuantity?.toPayload(),
      latestObservedTime = model.latestObservedTime,
      notes = model.processingNotes,
      photoFilenames = model.photoFilenames.orNull(),
      plantsCollectedFrom = model.numberOfTrees,
      projectId = model.projectId,
      receivedDate = model.receivedDate,
      remainingQuantity = model.remaining?.toPayload(),
      source = model.source,
      speciesScientificName = model.species,
      speciesCommonName = model.speciesCommonName,
      speciesId = model.speciesId,
      state = AccessionStateV2.of(model.state),
      subLocation = model.subLocation,
      subsetCount = model.subsetCount,
      subsetWeight = model.subsetWeightQuantity?.toPayload(),
      totalWithdrawnCount = model.totalWithdrawnCount,
      totalWithdrawnWeight = model.totalWithdrawnWeight?.toPayload(),
      viabilityPercent = model.totalViabilityPercent,
      viabilityTests = model.viabilityTests.map { GetViabilityTestPayload(it) }.orNull(),
      withdrawals = model.withdrawals.map { GetWithdrawalPayload(it) }.orNull(),
  )
}

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class CreateAccessionRequestPayloadV2(
    val bagNumbers: Set<String>? = null,
    val collectedDate: LocalDate? = null,
    val collectionSiteCity: String? = null,
    val collectionSiteCoordinates: Set<Geolocation>? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    val collectors: List<String?>? = null,
    val facilityId: FacilityId,
    val notes: String? = null,
    val plantId: String? = null,
    @Schema(description = "Estimated number of plants the seeds were collected from.")
    val plantsCollectedFrom: Int? = null,
    val projectId: ProjectId? = null,
    val receivedDate: LocalDate? = null,
    val source: DataSource? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionStateV2? = null,
    val subLocation: String? = null,
) {
  fun toModel(clock: Clock): AccessionModel {
    return AccessionModel(
        bagNumbers = bagNumbers.orEmpty(),
        clock = clock,
        collectedDate = collectedDate,
        collectionSiteCity = collectionSiteCity,
        collectionSiteCountryCode = collectionSiteCountryCode,
        collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
        collectionSiteLandowner = collectionSiteLandowner,
        collectionSiteName = collectionSiteName,
        collectionSiteNotes = collectionSiteNotes,
        collectionSource = collectionSource,
        collectors = collectors.orEmpty().filterNotNull(),
        facilityId = facilityId,
        founderId = plantId,
        geolocations = collectionSiteCoordinates.orEmpty(),
        numberOfTrees = plantsCollectedFrom,
        processingNotes = notes,
        projectId = projectId,
        receivedDate = receivedDate,
        source = source,
        speciesId = speciesId,
        state = state?.modelState ?: AccessionState.AwaitingCheckIn,
        subLocation = subLocation,
    )
  }
}

@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class UpdateAccessionRequestPayloadV2(
    val bagNumbers: Set<String>? = null,
    val collectedDate: LocalDate? = null,
    val collectionSiteCity: String? = null,
    val collectionSiteCoordinates: Set<Geolocation>? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    val collectors: List<String?>? = null,
    val dryingEndDate: LocalDate? = null,
    val facilityId: FacilityId? = null,
    val notes: String? = null,
    val plantId: String? = null,
    @Schema(description = "Estimated number of plants the seeds were collected from.")
    val plantsCollectedFrom: Int? = null,
    val projectId: ProjectId? = null,
    val receivedDate: LocalDate? = null,
    @Schema(
        description =
            "Quantity of seeds remaining in the accession. If this is different than the " +
                "existing value, it is considered a new observation, and the new value will " +
                "override any previously-calculated remaining quantities."
    )
    val remainingQuantity: SeedQuantityPayload? = null,
    @Schema(description = "Notes associated with remaining quantity updates if any.")
    val remainingQuantityNotes: String? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionStateV2,
    val subLocation: String? = null,
    val subsetCount: Int? = null,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\"."
    )
    val subsetWeight: SeedQuantityPayload? = null,
    val viabilityPercent: Int? = null,
) {
  fun applyToModel(model: AccessionModel): AccessionModel =
      model.copy(
          bagNumbers = bagNumbers.orEmpty(),
          collectedDate = collectedDate,
          collectionSiteCity = collectionSiteCity,
          collectionSiteCountryCode = collectionSiteCountryCode,
          collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
          collectionSiteLandowner = collectionSiteLandowner,
          collectionSiteName = collectionSiteName,
          collectionSiteNotes = collectionSiteNotes,
          collectionSource = collectionSource,
          collectors = collectors.orEmpty().filterNotNull(),
          dryingEndDate = dryingEndDate,
          facilityId = facilityId,
          founderId = plantId,
          geolocations = collectionSiteCoordinates.orEmpty(),
          numberOfTrees = plantsCollectedFrom,
          latestObservedQuantityCalculated = false,
          processingNotes = notes,
          projectId = projectId,
          receivedDate = receivedDate,
          remaining = remainingQuantity?.toModel(),
          speciesId = speciesId,
          state = state.modelState,
          subLocation = subLocation,
          subsetCount = subsetCount,
          subsetWeightQuantity = subsetWeight?.toModel(),
          totalViabilityPercent = viabilityPercent,
      )
}

data class CreateAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload

data class GetAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload

data class UpdateAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload
