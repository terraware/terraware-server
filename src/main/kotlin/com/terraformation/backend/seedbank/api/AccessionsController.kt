package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.CollectionSource
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.db.seedbank.ProcessingMethod
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.SourcePlantOrigin
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestTreatment
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
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
    val accession = accessionStore.fetchOneById(accessionId).toV1Compatible(clock)

    return GetAccessionResponsePayload(AccessionPayload(accession, clock))
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
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String? = null,
    val facilityId: FacilityId,
    val fieldNotes: String? = null,
    val founderId: String? = null,
    val geolocations: Set<Geolocation>? = null,
    @Schema(
        deprecated = true, description = "Backward-compatibility alias for collectionSiteLandowner")
    val landowner: String? = null,
    val numberOfTrees: Int? = null,
    val receivedDate: LocalDate? = null,
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
        collectors = collectors.orEmpty(),
        facilityId = facilityId,
        fieldNotes = fieldNotes,
        founderId = founderId,
        geolocations = geolocations.orEmpty(),
        numberOfTrees = numberOfTrees,
        receivedDate = receivedDate,
        source = source ?: DataSource.Web,
        sourcePlantOrigin = sourcePlantOrigin,
        species = species,
    )
  }
}

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
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String? = null,
    val facilityId: FacilityId? = null,
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
    val processingMethod: ProcessingMethod? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val processingStartDate: LocalDate? = null,
    val receivedDate: LocalDate? = null,
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
          collectors = collectors.orEmpty(),
          cutTestSeedsCompromised = cutTestSeedsCompromised,
          cutTestSeedsEmpty = cutTestSeedsEmpty,
          cutTestSeedsFilled = cutTestSeedsFilled,
          dryingEndDate = dryingEndDate,
          dryingMoveDate = dryingMoveDate,
          dryingStartDate = dryingStartDate,
          facilityId = facilityId,
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
    @Schema(deprecated = true, description = "Backward-compatibility alias for collectionSiteNotes")
    val environmentalNotes: String?,
    val estimatedSeedCount: Int?,
    val facilityId: FacilityId,
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
    val processingMethod: ProcessingMethod?,
    val processingNotes: String?,
    val processingStaffResponsible: String?,
    val processingStartDate: LocalDate?,
    val receivedDate: LocalDate?,
    @Schema(
        description =
            "Number or weight of seeds remaining for withdrawal and testing. Calculated by the " +
                "server when the accession's total size is known.")
    val remainingQuantity: SeedQuantityPayload?,
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
      model.collectionSiteNotes,
      model.estimatedSeedCount,
      model.facilityId ?: throw IllegalArgumentException("Accession did not have a facility ID"),
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
      model.processingMethod,
      model.processingNotes,
      model.processingStaffResponsible,
      model.processingStartDate,
      model.receivedDate,
      model.remaining?.toPayload(),
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
      model.viabilityTests
          .filter { it.testType != ViabilityTestType.Cut }
          .map { ViabilityTestPayload(it) }
          .orNull(),
      model.withdrawals
          .filter { withdrawal ->
            // If this withdrawal is for a viability test, only include it if the test is something
            // other than a cut test. Do a linear search to find the test with the right ID for each
            // withdrawal; accessions never have more than two or three tests, so it's not worth
            // building a more sophisticated index here.
            val viabilityTestForWithdrawal =
                withdrawal.viabilityTestId?.let { testId ->
                  model.viabilityTests.firstOrNull { it.id == testId }
                }
            viabilityTestForWithdrawal == null ||
                viabilityTestForWithdrawal.testType != ViabilityTestType.Cut
          }
          .map { WithdrawalPayload(it) }
          .orNull(),
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

fun SeedQuantityModel.toPayload() = SeedQuantityPayload(this)

/**
 * Test types that are compatible with the v1 API. The v2 API will change cut tests from accession
 * attributes ([AccessionModel.cutTestSeedsEmpty] and so on) to a third category of test that can be
 * mixed with lab and nursery tests, but clients of the v1 API won't know what to do with tests of
 * the new type. So we map them to lab tests, which will be a lossy transformation but will at least
 * represent them in _some_ form.
 */
enum class ViabilityTestTypeV1(val v2Type: ViabilityTestType) {
  Lab(ViabilityTestType.Lab),
  Nursery(ViabilityTestType.Nursery);

  companion object {
    @JvmStatic
    fun of(testType: ViabilityTestType): ViabilityTestTypeV1 =
        when (testType) {
          ViabilityTestType.Cut,
          ViabilityTestType.Lab -> Lab
          ViabilityTestType.Nursery -> Nursery
        }
  }
}

/**
 * Substrates that are compatible with the v1 API. The v2 API adds some new options and renames some
 * existing ones.
 */
enum class ViabilityTestSubstrateV1(
    val v2Type: ViabilityTestSubstrate,
    @get:JsonValue val displayName: String
) {
  AgarPetriDish(ViabilityTestSubstrate.Agar, "Agar Petri Dish"),
  NurseryMedia(ViabilityTestSubstrate.NurseryMedia, "Nursery Media"),
  PaperPetriDish(ViabilityTestSubstrate.Paper, "Paper Petri Dish"),
  Other(ViabilityTestSubstrate.Other, "Other");

  companion object {
    private val byDisplayName = values().associateBy { it.displayName }

    @JvmStatic
    fun of(substrate: ViabilityTestSubstrate?): ViabilityTestSubstrateV1? =
        when (substrate) {
          ViabilityTestSubstrate.Agar -> AgarPetriDish
          ViabilityTestSubstrate.NurseryMedia -> NurseryMedia
          ViabilityTestSubstrate.Other -> Other
          ViabilityTestSubstrate.Paper -> PaperPetriDish
          else -> null
        }

    @JsonCreator
    @JvmStatic
    fun forDisplayName(name: String) =
        byDisplayName[name] ?: throw IllegalArgumentException("Unrecognized value: $name")
  }
}

/**
 * Treatments that are compatible with the v1 API. The v2 API renames "GA3" to "Chemical" and adds
 * "Light".
 */
enum class ViabilityTestTreatmentV1(val v2Type: ViabilityTestTreatment) {
  Soak(ViabilityTestTreatment.Soak),
  Scarify(ViabilityTestTreatment.Scarify),
  GA3(ViabilityTestTreatment.Chemical),
  Stratification(ViabilityTestTreatment.Stratification),
  Other(ViabilityTestTreatment.Other);

  companion object {
    @JvmStatic
    fun of(treatment: ViabilityTestTreatment?): ViabilityTestTreatmentV1? =
        when (treatment) {
          ViabilityTestTreatment.Chemical -> GA3
          ViabilityTestTreatment.Light,
          ViabilityTestTreatment.Other -> Other
          ViabilityTestTreatment.Scarify -> Scarify
          ViabilityTestTreatment.Soak -> Soak
          ViabilityTestTreatment.Stratification -> Stratification
          null -> null
        }
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ViabilityTestPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this viability test. Null when creating a new test.",
        type = "string")
    val id: ViabilityTestId? = null,
    @Schema(
        description = "Which type of test is described. At most one of each test type is allowed.")
    val testType: ViabilityTestTypeV1,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val seedType: ViabilityTestSeedType? = null,
    val substrate: ViabilityTestSubstrateV1? = null,
    val treatment: ViabilityTestTreatmentV1? = null,
    val notes: String? = null,
    @Schema(
        description =
            "Quantity of seeds remaining. For weight-based accessions, this is user input and " +
                "is required. For count-based accessions, it is calculated by the server and " +
                "ignored on input.")
    val remainingQuantity: SeedQuantityPayload? = null,
    val staffResponsible: String? = null,
    val seedsSown: Int? = null,
    @Valid val testResults: List<ViabilityTestResultPayload>? = null,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
) {
  constructor(
      model: ViabilityTestModel
  ) : this(
      model.id,
      ViabilityTestTypeV1.of(model.testType),
      model.startDate,
      model.endDate,
      model.seedType,
      ViabilityTestSubstrateV1.of(model.substrate),
      ViabilityTestTreatmentV1.of(model.treatment),
      model.notes,
      model.remaining?.toPayload(),
      model.staffResponsible,
      model.seedsTested,
      model.testResults?.map { ViabilityTestResultPayload(it) },
      model.viabilityPercent,
      model.totalSeedsGerminated,
  )

  fun toModel() =
      ViabilityTestModel(
          endDate = endDate,
          id = id,
          notes = notes,
          remaining = remainingQuantity?.toModel(),
          seedsTested = seedsSown,
          seedType = seedType,
          staffResponsible = staffResponsible,
          startDate = startDate,
          substrate = substrate?.v2Type,
          testResults = testResults?.map { it.toModel() },
          testType = testType.v2Type,
          treatment = treatment?.v2Type,
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

  fun toModel() = ViabilityTestResultModel(null, recordingDate, seedsGerminated, null)
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
          id = id,
          notes = notes,
          purpose = purpose,
          remaining = remainingQuantity?.toModel(),
          staffResponsible = staffResponsible,
          viabilityTestId = viabilityTestId,
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
