package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionState
import com.terraformation.backend.db.CollectionSource
import com.terraformation.backend.db.DataSource
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.ProcessingMethod
import com.terraformation.backend.db.RareType
import com.terraformation.backend.db.SeedQuantityUnits
import com.terraformation.backend.db.SourcePlantOrigin
import com.terraformation.backend.db.SpeciesEndangeredType
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestSeedType
import com.terraformation.backend.db.ViabilityTestSubstrate
import com.terraformation.backend.db.ViabilityTestTreatment
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.db.WithdrawalId
import com.terraformation.backend.db.WithdrawalPurpose
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionActive
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.AccessionModel
import com.terraformation.backend.seedbank.model.Geolocation
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import com.terraformation.backend.seedbank.model.WithdrawalModel
import com.terraformation.backend.seedbank.model.isV1Compatible
import com.terraformation.backend.util.orNull
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.validation.Valid
import javax.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/accessions")
@RestController
@SeedBankAppEndpoint
class AccessionsController(
    private val accessionService: AccessionService,
    private val accessionStore: AccessionStore,
    private val clock: Clock
) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number and ID.")
  @Operation(summary = "Create a new accession.")
  @PostMapping
  fun create(@RequestBody payload: CreateAccessionRequestPayload): CreateAccessionResponsePayload {
    val updatedPayload = accessionStore.create(payload.toModel())
    return CreateAccessionResponsePayload(AccessionPayload(updatedPayload, clock))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{id}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable("id") accessionId: AccessionId,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved.")
      simulate: Boolean?
  ): UpdateAccessionResponsePayload {
    val updatedModel =
        if (simulate == true) {
          accessionStore.dryRun(payload.toModel(accessionId))
        } else {
          accessionStore.updateAndFetch(payload.toModel(accessionId))
        }
    return UpdateAccessionResponsePayload(AccessionPayload(updatedModel, clock))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{id}")
  @Operation(summary = "Retrieve an existing accession.")
  fun read(@PathVariable("id") accessionId: AccessionId): GetAccessionResponsePayload {
    val accession = accessionStore.fetchOneById(accessionId)

    // If this accession was created/updated with manual state management, it might have a state
    // that didn't exist in the v1 API. In that case, figure out what its calculated v1 state would
    // have been, and return that.
    val v1CompatibleAccession =
        if (accession.state?.isV1Compatible == true) {
          accession
        } else {
          accession.copy(isManualState = false).withCalculatedValues(clock)
        }

    return GetAccessionResponsePayload(AccessionPayload(v1CompatibleAccession, clock))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/{id}")
  fun delete(@PathVariable("id") accessionId: AccessionId): SimpleSuccessResponsePayload {
    accessionService.deleteAccession(accessionId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @Operation(summary = "Marks an accession as checked in.")
  @PostMapping("/{id}/checkIn")
  fun checkIn(@PathVariable("id") accessionId: AccessionId): UpdateAccessionResponsePayload {
    val accession = accessionStore.checkIn(accessionId)
    return UpdateAccessionResponsePayload(AccessionPayload(accession, clock))
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @Operation(summary = "Gets the history of changes to an accession.")
  @GetMapping("/{id}/history")
  fun getAccessionHistory(
      @PathVariable("id") accessionId: AccessionId
  ): GetAccessionHistoryResponsePayload {
    val entries = accessionStore.fetchHistory(accessionId).map { AccessionHistoryEntryPayload(it) }
    return GetAccessionHistoryResponsePayload(entries)
  }
}

/**
 * Returns the list of collectors from an incoming request payload that might be coming from a
 * client that doesn't know about the new unified `collectors` field. The client will remove the old
 * fields when it starts using the new one, so if the old fields are present, use them and ignore
 * the new one.
 */
private fun backwardCompatibleCollectors(
    primaryCollector: String?,
    secondaryCollectors: List<String>?,
    collectors: List<String>?
): List<String> {
  return if (primaryCollector != null || secondaryCollectors != null) {
    listOfNotNull(primaryCollector) + secondaryCollectors.orEmpty()
  } else {
    collectors.orEmpty()
  }
}

/** Maps a source plant origin to the equivalent collection source for backward compatibility. */
private fun SourcePlantOrigin.toCollectionSource(): CollectionSource =
    when (this) {
      SourcePlantOrigin.Outplant -> CollectionSource.Cultivated
      SourcePlantOrigin.Wild -> CollectionSource.Wild
    }

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class CreateAccessionRequestPayload(
    val bagNumbers: Set<String>? = null,
    val collectedDate: LocalDate? = null,
    val collectionSiteCity: String? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    val collectors: List<String>? = null,
    val endangered: SpeciesEndangeredType? = null,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String? = null,
    val facilityId: FacilityId,
    val family: String? = null,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation>? = null,
    @Schema(
        deprecated = true, description = "Backward-compatibility alias for collectionSiteLandowner")
    val landowner: String? = null,
    val numberOfTrees: Int? = null,
    @Schema(
        deprecated = true,
    )
    val primaryCollector: String? = null,
    val rare: RareType? = null,
    val receivedDate: LocalDate? = null,
    @Schema(
        deprecated = true,
    )
    val secondaryCollectors: List<String>? = null,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteName")
    val siteLocation: String? = null,
    val source: DataSource? = null,
    val sourcePlantOrigin: SourcePlantOrigin? = null,
    val species: String? = null,
) {
  fun toModel(): AccessionModel {
    return AccessionModel(
        bagNumbers = bagNumbers.orEmpty(),
        collectedDate = collectedDate,
        collectionSiteCity = collectionSiteCity,
        collectionSiteCountryCode = collectionSiteCountryCode,
        collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
        collectionSiteLandowner = landowner ?: collectionSiteLandowner,
        collectionSiteName = siteLocation ?: collectionSiteName,
        collectionSiteNotes = environmentalNotes ?: collectionSiteNotes,
        collectionSource = sourcePlantOrigin?.toCollectionSource() ?: collectionSource,
        collectors =
            backwardCompatibleCollectors(primaryCollector, secondaryCollectors, collectors),
        endangered = endangered,
        facilityId = facilityId,
        family = family,
        fieldNotes = fieldNotes,
        founderId = founderId,
        geolocations = geolocations.orEmpty(),
        numberOfTrees = numberOfTrees,
        rare = rare,
        receivedDate = receivedDate,
        source = source ?: DataSource.Web,
        sourcePlantOrigin = sourcePlantOrigin,
        species = species,
    )
  }
}

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class UpdateAccessionRequestPayload(
    val bagNumbers: Set<String>? = null,
    val collectedDate: LocalDate? = null,
    val collectionSiteCity: String? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    val collectors: List<String>? = null,
    val cutTestSeedsCompromised: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsFilled: Int? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val dryingStartDate: LocalDate? = null,
    val endangered: SpeciesEndangeredType? = null,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String? = null,
    val facilityId: FacilityId? = null,
    val family: String? = null,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation>? = null,
    @Schema(
        description =
            "Initial size of accession. The units of this value must match the measurement type " +
                "in \"processingMethod\".")
    val initialQuantity: SeedQuantityPayload? = null,
    @Schema(
        deprecated = true, description = "Backward-compatibility alias for collectionSiteLandowner")
    val landowner: String? = null,
    val numberOfTrees: Int? = null,
    val nurseryStartDate: LocalDate? = null,
    @Schema(
        deprecated = true,
    )
    val primaryCollector: String? = null,
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val processingStartDate: LocalDate? = null,
    val rare: RareType? = null,
    val receivedDate: LocalDate? = null,
    @Schema(
        deprecated = true,
    )
    val secondaryCollectors: List<String>? = null,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteName")
    val siteLocation: String? = null,
    val sourcePlantOrigin: SourcePlantOrigin? = null,
    val species: String? = null,
    val storageLocation: String? = null,
    val storageNotes: String? = null,
    val storagePackets: Int? = null,
    val storageStaffResponsible: String? = null,
    val storageStartDate: LocalDate? = null,
    val subsetCount: Int? = null,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    private val subsetWeight: SeedQuantityPayload? = null,
    val targetStorageCondition: StorageCondition? = null,
    @Valid val viabilityTests: List<ViabilityTestPayload>? = null,
    @Valid val withdrawals: List<WithdrawalPayload>? = null,
) {
  fun toModel(id: AccessionId) =
      AccessionModel(
          bagNumbers = bagNumbers.orEmpty(),
          collectedDate = collectedDate,
          collectionSiteCity = collectionSiteCity,
          collectionSiteCountryCode = collectionSiteCountryCode,
          collectionSiteCountrySubdivision = collectionSiteCountrySubdivision,
          collectionSiteLandowner = landowner ?: collectionSiteLandowner,
          collectionSiteName = siteLocation ?: collectionSiteName,
          collectionSiteNotes = environmentalNotes ?: collectionSiteNotes,
          collectionSource = sourcePlantOrigin?.toCollectionSource() ?: collectionSource,
          collectors =
              backwardCompatibleCollectors(primaryCollector, secondaryCollectors, collectors),
          cutTestSeedsCompromised = cutTestSeedsCompromised,
          cutTestSeedsEmpty = cutTestSeedsEmpty,
          cutTestSeedsFilled = cutTestSeedsFilled,
          dryingEndDate = dryingEndDate,
          dryingMoveDate = dryingMoveDate,
          dryingStartDate = dryingStartDate,
          endangered = endangered,
          facilityId = facilityId,
          family = family,
          fieldNotes = fieldNotes,
          founderId = founderId,
          geolocations = geolocations.orEmpty(),
          id = id,
          numberOfTrees = numberOfTrees,
          nurseryStartDate = nurseryStartDate,
          processingMethod = processingMethod,
          processingNotes = processingNotes,
          processingStaffResponsible = processingStaffResponsible,
          processingStartDate = processingStartDate,
          rare = rare,
          receivedDate = receivedDate,
          sourcePlantOrigin = sourcePlantOrigin,
          species = species,
          storageLocation = storageLocation,
          storageNotes = storageNotes,
          storagePackets = storagePackets,
          storageStaffResponsible = storageStaffResponsible,
          storageStartDate = storageStartDate,
          subsetCount = subsetCount,
          subsetWeightQuantity = subsetWeight?.toModel(),
          targetStorageCondition = targetStorageCondition,
          total = initialQuantity?.toModel(),
          viabilityTests = viabilityTests.orEmpty().map { it.toModel() },
          withdrawals = withdrawals.orEmpty().map { it.toModel() },
      )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema
data class AccessionPayload(
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
    val checkedInTime: Instant?,
    val collectedDate: LocalDate?,
    val collectionSiteCity: String? = null,
    val collectionSiteCountryCode: String? = null,
    val collectionSiteCountrySubdivision: String? = null,
    val collectionSiteLandowner: String? = null,
    val collectionSiteName: String? = null,
    val collectionSiteNotes: String? = null,
    val collectionSource: CollectionSource? = null,
    @Schema(description = "Names of the people who collected the seeds.")
    val collectors: List<String>?,
    val cutTestSeedsCompromised: Int?,
    val cutTestSeedsEmpty: Int?,
    val cutTestSeedsFilled: Int?,
    val dryingEndDate: LocalDate?,
    val dryingMoveDate: LocalDate?,
    val dryingStartDate: LocalDate?,
    val endangered: SpeciesEndangeredType?,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String?,
    val estimatedSeedCount: Int?,
    val facilityId: FacilityId,
    val family: String?,
    val fieldNotes: String?,
    val founderId: String?,
    val geolocations: Set<Geolocation>?,
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
        deprecated = true, description = "Backward-compatibility alias for collectionSiteLandowner")
    val landowner: String?,
    val latestViabilityPercent: Int?,
    val latestViabilityTestDate: LocalDate?,
    val numberOfTrees: Int?,
    val nurseryStartDate: LocalDate?,
    val photoFilenames: List<String>?,
    @Schema(
        deprecated = true,
    )
    val primaryCollector: String?,
    val processingMethod: ProcessingMethod?,
    val processingNotes: String?,
    val processingStaffResponsible: String?,
    val processingStartDate: LocalDate?,
    val rare: RareType?,
    val receivedDate: LocalDate?,
    @Schema(
        description =
            "Number or weight of seeds remaining for withdrawal and testing. Calculated by the " +
                "server when the accession's total size is known.")
    val remainingQuantity: SeedQuantityPayload?,
    @Schema(
        deprecated = true,
    )
    val secondaryCollectors: List<String>?,
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteName")
    val siteLocation: String?,
    @Schema(
        description =
            "Which application this accession originally came from. This is currently based on " +
                "the presence of the deviceInfo field.")
    val source: DataSource?,
    val sourcePlantOrigin: SourcePlantOrigin?,
    @Schema(
        description = "Scientific name of the species.",
    )
    val species: String?,
    @Schema(
        description = "Common name of the species, if defined.",
    )
    val speciesCommonName: String?,
    @Schema(
        description = "Server-generated unique ID of the species.",
    )
    val speciesId: SpeciesId?,
    @Schema(
        description =
            "Server-calculated accession state. Can change due to modifications to accession data " +
                "or based on passage of time.")
    val state: AccessionState,
    val storageCondition: StorageCondition?,
    val storageLocation: String?,
    val storagePackets: Int?,
    val storageNotes: String?,
    val storageStaffResponsible: String?,
    val storageStartDate: LocalDate?,
    val subsetCount: Int?,
    @Schema(
        description =
            "Weight of subset of seeds. Units must be a weight measurement, not \"Seeds\".")
    val subsetWeight: SeedQuantityPayload?,
    val targetStorageCondition: StorageCondition?,
    @Schema(description = "Total quantity of all past withdrawals, including viability tests.")
    val totalPastWithdrawalQuantity: SeedQuantityPayload?,
    @Schema(description = "Total quantity of scheduled withdrawals, not counting viability tests.")
    val totalScheduledNonTestQuantity: SeedQuantityPayload?,
    @Schema(description = "Total quantity of scheduled withdrawals for viability tests.")
    val totalScheduledTestQuantity: SeedQuantityPayload?,
    @Schema(description = "Total quantity of scheduled withdrawals, including viability tests.")
    val totalScheduledWithdrawalQuantity: SeedQuantityPayload?,
    val totalViabilityPercent: Int?,
    @Schema(
        description =
            "Total quantity of all past and scheduled withdrawals, including viability tests.")
    val totalWithdrawalQuantity: SeedQuantityPayload?,
    val viabilityTests: List<ViabilityTestPayload>?,
    val withdrawals: List<WithdrawalPayload>?,
) {
  constructor(
      model: AccessionModel,
      clock: Clock
  ) : this(
      model.accessionNumber ?: throw IllegalArgumentException("Accession did not have a number"),
      model.active ?: AccessionActive.Active,
      model.bagNumbers.orNull(),
      model.checkedInTime,
      model.collectedDate,
      model.collectionSiteCity,
      model.collectionSiteCountryCode,
      model.collectionSiteCountrySubdivision,
      model.collectionSiteLandowner,
      model.collectionSiteName,
      model.collectionSiteNotes,
      model.collectionSource,
      model.collectors.orNull(),
      model.cutTestSeedsCompromised,
      model.cutTestSeedsEmpty,
      model.cutTestSeedsFilled,
      model.dryingEndDate,
      model.dryingMoveDate,
      model.dryingStartDate,
      model.endangered,
      model.collectionSiteNotes,
      model.estimatedSeedCount,
      model.facilityId ?: throw IllegalArgumentException("Accession did not have a facility ID"),
      model.family,
      model.fieldNotes,
      model.founderId,
      model.geolocations.orNull(),
      model.id ?: throw IllegalArgumentException("Accession did not have an ID"),
      model.total?.toPayload(),
      model.collectionSiteLandowner,
      model.latestViabilityPercent,
      model.latestViabilityTestDate,
      model.numberOfTrees,
      model.nurseryStartDate,
      model.photoFilenames.orNull(),
      model.collectors.getOrNull(0),
      model.processingMethod,
      model.processingNotes,
      model.processingStaffResponsible,
      model.processingStartDate,
      model.rare,
      model.receivedDate,
      model.remaining?.toPayload(),
      model.collectors.drop(1).orNull(),
      model.collectionSiteName,
      model.source,
      model.sourcePlantOrigin,
      model.species,
      model.speciesCommonName,
      model.speciesId,
      model.state ?: AccessionState.Pending,
      model.storageCondition,
      model.storageLocation,
      model.storagePackets,
      model.storageNotes,
      model.storageStaffResponsible,
      model.storageStartDate,
      model.subsetCount,
      model.subsetWeightQuantity?.toPayload(),
      model.targetStorageCondition,
      model.calculateTotalPastWithdrawalQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledNonTestQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledTestQuantity(clock)?.toPayload(),
      model.calculateTotalScheduledWithdrawalQuantity(clock)?.toPayload(),
      model.totalViabilityPercent,
      model.calculateTotalWithdrawalQuantity(clock)?.toPayload(),
      model.viabilityTests.map { ViabilityTestPayload(it) }.orNull(),
      model.withdrawals.map { WithdrawalPayload(it) }.orNull(),
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Represents a quantity of seeds, measured either in individual seeds or by weight.")
data class SeedQuantityPayload(
    @Schema(
        description =
            "Number of units of seeds. If \"units\" is \"Seeds\", this is the number of seeds " +
                "and must be an integer. Otherwise it is a measurement in the weight units " +
                "specified in the \"units\" field, and may have a fractional part.")
    @PositiveOrZero
    val quantity: BigDecimal,
    val units: SeedQuantityUnits,
    @Schema(
        description =
            "If this quantity is a weight measurement, the weight in grams. This is not set if " +
                "the \"units\" field is \"Seeds\". This is always calculated on the server side " +
                "and is ignored on input.",
        readOnly = true)
    val grams: BigDecimal? = null
) {
  constructor(model: SeedQuantityModel) : this(model.quantity, model.units, model.grams)

  fun toModel() = SeedQuantityModel(quantity, units)
}

private fun SeedQuantityModel.toPayload() = SeedQuantityPayload(this)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ViabilityTestPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this viability test. Null when creating a new test.",
        type = "string")
    val id: ViabilityTestId? = null,
    @Schema(
        description = "Which type of test is described. At most one of each test type is allowed.")
    val testType: ViabilityTestType,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val seedType: ViabilityTestSeedType? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val treatment: ViabilityTestTreatment? = null,
    val notes: String? = null,
    @Schema(
        description =
            "Quantity of seeds remaining. For weight-based accessions, this is user input and " +
                "is required. For count-based accessions, it is calculated by the server and " +
                "ignored on input.")
    val remainingQuantity: SeedQuantityPayload? = null,
    val staffResponsible: String? = null,
    val seedsSown: Int? = null,
    @Schema(
        description =
            "If true, this viability test's results are used as the viability percentage for the " +
                "accession as a whole. At most one test can be marked as selected.",
        defaultValue = "false")
    val selected: Boolean = false,
    @Valid val testResults: List<ViabilityTestResultPayload>? = null,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
) {
  constructor(
      model: ViabilityTestModel
  ) : this(
      model.id,
      model.testType,
      model.startDate,
      model.endDate,
      model.seedType,
      model.substrate,
      model.treatment,
      model.notes,
      model.remaining?.toPayload(),
      model.staffResponsible,
      model.seedsSown,
      model.selected,
      model.testResults?.map { ViabilityTestResultPayload(it) },
      model.totalPercentGerminated,
      model.totalSeedsGerminated,
  )

  fun toModel() =
      ViabilityTestModel(
          testResults = testResults?.map { it.toModel() },
          id = id,
          endDate = endDate,
          notes = notes,
          seedsSown = seedsSown,
          seedType = seedType,
          selected = selected,
          staffResponsible = staffResponsible,
          startDate = startDate,
          substrate = substrate,
          remaining = remainingQuantity?.toModel(),
          testType = testType,
          treatment = treatment,
      )
}

data class ViabilityTestResultPayload(
    val recordingDate: LocalDate,
    @JsonProperty(
        required = true,
    )
    val seedsGerminated: Int
) {
  constructor(model: ViabilityTestResultModel) : this(model.recordingDate, model.seedsGerminated)

  fun toModel() = ViabilityTestResultModel(null, null, recordingDate, seedsGerminated)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WithdrawalPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this withdrawal, its ID. Omit when creating a new " +
                "withdrawal.")
    val id: WithdrawalId? = null,
    val date: LocalDate,
    val purpose: WithdrawalPurpose? = null,
    val destination: String? = null,
    val notes: String? = null,
    @Schema(
        description =
            "Quantity of seeds remaining. For weight-based accessions, this is user input and " +
                "is required. For count-based accessions, it is calculated by the server and " +
                "ignored on input.")
    val remainingQuantity: SeedQuantityPayload? = null,
    val staffResponsible: String? = null,
    @Schema(
        description =
            "If this withdrawal is of purpose \"Germination Testing\", the ID of the test it is " +
                "associated with. This is always set by the server and cannot be modified.")
    val viabilityTestId: ViabilityTestId? = null,
    @Schema(
        description =
            "For weight-based accessions, the difference between the weight remaining before " +
                "this withdrawal and the weight remaining after it. This is a server-calculated " +
                "value and is ignored on input.",
        readOnly = true)
    val weightDifference: SeedQuantityPayload? = null,
    @Schema(
        description =
            "Quantity of seeds withdrawn. For viability testing withdrawals, this is always " +
                "the same as the test's \"seedsSown\" value, if that value is present. " +
                "Otherwise, it is a user-supplied value. For count-based accessions, the units " +
                "must always be \"Seeds\". For weight-based accessions, the units may either be " +
                "a weight measurement or \"Seeds\".")
    val withdrawnQuantity: SeedQuantityPayload? = null,
    @Schema(
        description =
            "The best estimate of the number of seeds withdrawn. This is the same as " +
                "\"withdrawnQuantity\" if that is present, or else the same as " +
                "\"weightDifference\" if this is a weight-based accession. If this is a " +
                "count-based accession and \"withdrawnQuantity\" does not have a value, " +
                "this field will not be present. This is a server-calculated value and is " +
                "ignored on input.",
        readOnly = true)
    val estimatedQuantity: SeedQuantityPayload? = null,
) {
  constructor(
      model: WithdrawalModel
  ) : this(
      model.id,
      model.date,
      model.purpose,
      model.destination,
      model.notes,
      model.remaining?.toPayload(),
      model.staffResponsible,
      model.viabilityTestId,
      model.weightDifference?.toPayload(),
      model.withdrawn?.toPayload(),
      model.calculateEstimatedQuantity()?.toPayload(),
  )

  fun toModel() =
      WithdrawalModel(
          date = date,
          destination = destination,
          viabilityTestId = viabilityTestId,
          id = id,
          notes = notes,
          purpose = purpose,
          remaining = remainingQuantity?.toModel(),
          staffResponsible = staffResponsible,
          withdrawn = withdrawnQuantity?.toModel(),
      )
}

data class AccessionHistoryEntryPayload(
    val date: LocalDate,
    @Schema(
        description = "Human-readable description of the event. Does not include date or userName.",
        example = "updated the status to Drying")
    val description: String,
    @Schema(description = "Full name of the person responsible for the event, if known.")
    val fullName: String?,
    val type: AccessionHistoryType,
) {
  constructor(
      model: AccessionHistoryModel
  ) : this(model.date, model.description, model.fullName, model.type)
}

data class CreateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class UpdateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(description = "History of changes in descending time order (newest first.)"))
    val history: List<AccessionHistoryEntryPayload>
) : SuccessResponsePayload
