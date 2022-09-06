package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.CollectionSource
import com.terraformation.backend.db.DataSource
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.util.orNull
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.validation.Valid
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
    private val clock: Clock,
) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number and ID.")
  @Operation(summary = "Creates a new accession.")
  @PostMapping
  fun createAccession(
      @RequestBody payload: CreateAccessionRequestPayloadV2
  ): CreateAccessionResponsePayloadV2 {
    val updatedPayload = accessionStore.create(payload.toModel())
    return CreateAccessionResponsePayloadV2(AccessionPayloadV2(updatedPayload))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{id}")
  fun updateAccession(
      @RequestBody payload: UpdateAccessionRequestPayloadV2,
      @PathVariable("id") accessionId: AccessionId,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved.")
      simulate: Boolean?
  ): UpdateAccessionResponsePayloadV2 {
    val existing = accessionStore.fetchOneById(accessionId)
    val editedModel = payload.applyToModel(existing)

    val updatedModel =
        if (simulate == true) {
          accessionStore.dryRun(editedModel)
        } else {
          accessionStore.updateAndFetch(editedModel)
        }
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(updatedModel))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Retrieve an existing accession.")
  fun getAccession(@PathVariable("id") accessionId: AccessionId): GetAccessionResponsePayloadV2 {
    val accession = accessionStore.fetchOneById(accessionId).toV2Compatible(clock)

    return GetAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class AccessionPayloadV2(
    @Schema(
        description =
            "Server-generated human-readable identifier for the accession. This is unique " +
                "within a single seed bank, but different seed banks may have accessions with " +
                "the same number.")
    val accessionNumber: String,
    @Schema(
        description = "Server-calculated active indicator. This is based on the accession's state.")
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
    @Schema(description = "Names of the people who collected the seeds.")
    val collectors: List<String>?,
    val dryingEndDate: LocalDate?,
    val estimatedSeedCount: Int?,
    val facilityId: FacilityId,
    val family: String?,
    val founderId: String?,
    @Schema(
        description =
            "Server-generated unique identifier for the accession. This is unique across all " +
                "seed banks, but is not suitable for display to end users.")
    val id: AccessionId,
    @Schema(
        description =
            "Initial size of accession. The units of this value must match the measurement type " +
                "in \"processingMethod\".")
    val initialQuantity: SeedQuantityPayload?,
    @Schema(
        description =
            "Most recent user observation of seeds remaining in the accession. This is not " +
                "directly editable; it is updated by the server whenever the " +
                "\"remainingQuantity\" field is edited.")
    val latestObservedQuantity: SeedQuantityPayload?,
    @Schema(
        description =
            "Time of most recent user observation of seeds remaining in the accession. This is " +
                "updated by the server whenever the \"remainingQuantity\" field is edited.")
    val latestObservedTime: Instant?,
    val latestViabilityPercent: Int?,
    val latestViabilityTestDate: LocalDate?,
    val notes: String?,
    val photoFilenames: List<String>?,
    val plantsCollectedFromMax: Int?,
    val plantsCollectedFromMin: Int?,
    val processingMethod: ProcessingMethod?,
    val receivedDate: LocalDate?,
    @Schema(
        description =
            "Number or weight of seeds remaining for withdrawal and testing. May be calculated " +
                "by the server after withdrawals.")
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
    val state: AccessionState,
    val storageLocation: String?,
    val subsetCount: Int?,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    val subsetWeight: SeedQuantityPayload?,
    val totalViabilityPercent: Int?,
    val viabilityTests: List<ViabilityTestPayload>?,
    val withdrawals: List<GetWithdrawalPayload>?,
) {
  constructor(
      model: AccessionModel
  ) : this(
      accessionNumber = model.accessionNumber
              ?: throw IllegalArgumentException("Accession did not have a number"),
      active = model.active ?: AccessionActive.Active,
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
      estimatedSeedCount = model.estimatedSeedCount,
      facilityId = model.facilityId
              ?: throw IllegalArgumentException("Accession did not have a facility ID"),
      family = model.family,
      founderId = model.founderId,
      id = model.id ?: throw IllegalArgumentException("Accession did not have an ID"),
      initialQuantity = model.total?.toPayload(),
      latestObservedQuantity = model.latestObservedQuantity?.toPayload(),
      latestObservedTime = model.latestObservedTime,
      latestViabilityPercent = model.latestViabilityPercent,
      latestViabilityTestDate = model.latestViabilityTestDate,
      notes = model.processingNotes,
      photoFilenames = model.photoFilenames.orNull(),
      // TODO replace with max/min plants
      plantsCollectedFromMax = model.numberOfTrees,
      plantsCollectedFromMin = model.numberOfTrees,
      processingMethod = model.processingMethod,
      receivedDate = model.receivedDate,
      remainingQuantity = model.remaining?.toPayload(),
      source = model.source,
      speciesScientificName = model.species,
      speciesCommonName = model.speciesCommonName,
      speciesId = model.speciesId,
      state = model.state ?: AccessionState.Pending,
      storageLocation = model.storageLocation,
      subsetCount = model.subsetCount,
      subsetWeight = model.subsetWeightQuantity?.toPayload(),
      totalViabilityPercent = model.totalViabilityPercent,
      viabilityTests = model.viabilityTests.map { ViabilityTestPayload(it) }.orNull(),
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
    val collectors: List<String>? = null,
    val facilityId: FacilityId,
    val founderId: String? = null,
    val plantsCollectedFromMax: Int? = null,
    val plantsCollectedFromMin: Int? = null,
    val receivedDate: LocalDate? = null,
    val source: DataSource? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionState? = null,
    val storageLocation: String? = null,
) {
  fun toModel(): AccessionModel {
    return AccessionModel(
        bagNumbers = bagNumbers.orEmpty(),
        collectedDate = collectedDate,
        collectionSiteCity = collectionSiteCity,
        collectionSiteCountryCode = collectionSiteCountryCode,
        collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
        collectionSiteLandowner = collectionSiteLandowner,
        collectionSiteName = collectionSiteName,
        collectionSiteNotes = collectionSiteNotes,
        collectionSource = collectionSource,
        collectors = collectors.orEmpty(),
        facilityId = facilityId,
        founderId = founderId,
        geolocations = collectionSiteCoordinates.orEmpty(),
        isManualState = true,
        numberOfTrees = plantsCollectedFromMax ?: plantsCollectedFromMin,
        receivedDate = receivedDate,
        source = source,
        speciesId = speciesId,
        state = state,
        storageLocation = storageLocation,
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
    val collectors: List<String>? = null,
    val dryingEndDate: LocalDate? = null,
    val facilityId: FacilityId? = null,
    val founderId: String? = null,
    val notes: String? = null,
    val plantsCollectedFromMax: Int? = null,
    val plantsCollectedFromMin: Int? = null,
    val receivedDate: LocalDate? = null,
    @Schema(
        description =
            "Quantity of seeds remaining in the accession. If this is different than the " +
                "existing value, it is considered a new observation, and the new value will " +
                "override any previously-calculated remaining quantities.")
    val remainingQuantity: SeedQuantityPayload? = null,
    val speciesId: SpeciesId? = null,
    val state: AccessionState,
    val storageLocation: String? = null,
    val subsetCount: Int? = null,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    private val subsetWeight: SeedQuantityPayload? = null,
    @Valid val viabilityTests: List<ViabilityTestPayload>? = null,
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
          collectors = collectors.orEmpty(),
          dryingEndDate = dryingEndDate,
          facilityId = facilityId,
          founderId = founderId,
          geolocations = collectionSiteCoordinates.orEmpty(),
          isManualState = true,
          numberOfTrees = plantsCollectedFromMax ?: plantsCollectedFromMin,
          processingNotes = notes,
          receivedDate = receivedDate,
          remaining = remainingQuantity?.toModel(),
          speciesId = speciesId,
          state = state,
          storageLocation = storageLocation,
          subsetCount = subsetCount,
          subsetWeightQuantity = subsetWeight?.toModel(),
          viabilityTests = viabilityTests.orEmpty().map { it.toModel() },
      )
}

data class CreateAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload

data class GetAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload

data class UpdateAccessionResponsePayloadV2(val accession: AccessionPayloadV2) :
    SuccessResponsePayload
