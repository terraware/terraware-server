package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionNotFoundException
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.GerminationSeedType
import com.terraformation.seedbank.db.GerminationSubstrate
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.GerminationTreatment
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.SourcePlantOrigin
import com.terraformation.seedbank.db.SpeciesEndangeredType
import com.terraformation.seedbank.db.SpeciesRareType
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.AppDeviceFields
import com.terraformation.seedbank.model.ConcreteAccession
import com.terraformation.seedbank.model.Geolocation
import com.terraformation.seedbank.model.GerminationFields
import com.terraformation.seedbank.model.GerminationTestFields
import com.terraformation.seedbank.model.WithdrawalFields
import com.terraformation.seedbank.services.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
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

@RequestMapping("/api/v2/seedbank/accession")
@RestController
@SeedBankAppEndpoint
class AccessionController(private val accessionStore: AccessionStore) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number.")
  @Operation(summary = "Create a new accession.", operationId = "createv2")
  @PostMapping
  fun create(@RequestBody payload: CreateAccessionRequestPayload): CreateAccessionResponsePayload {
    val updatedPayload = accessionStore.create(payload)
    return CreateAccessionResponsePayload(AccessionPayload(updatedPayload))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.", operationId = "updatev2")
  @PutMapping("/{accessionNumber}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable accessionNumber: String,
      @RequestParam
      @Schema(
          description =
              "If true, do not actually save the accession; just return the result that would " +
                  "have been returned if it had been saved.")
      simulate: Boolean?
  ): UpdateAccessionResponsePayload {
    perClassLogger().debug("Payload $payload")
    try {
      val updatedModel =
          if (simulate == true) {
            accessionStore.dryRun(payload, accessionNumber)
          } else {
            accessionStore.updateAndFetch(payload, accessionNumber)
          }
      return UpdateAccessionResponsePayload(AccessionPayload(updatedModel))
    } catch (e: AccessionNotFoundException) {
      throw NotFoundException()
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{accessionNumber}")
  @Operation(summary = "Retrieve an existing accession.", operationId = "readv2")
  fun read(@PathVariable accessionNumber: String): GetAccessionResponsePayload {
    val accession =
        accessionStore.fetchByNumber(accessionNumber)
            ?: throw NotFoundException("The specified accession doesn't exist.")
    return GetAccessionResponsePayload(AccessionPayload(accession))
  }
}

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class CreateAccessionRequestPayload(
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val deviceInfo: DeviceInfoPayload? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val germinationTests: List<GerminationTestPayload>? = null,
    override val landowner: String? = null,
    override val numberOfTrees: Int? = null,
    override val primaryCollector: String? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
) : AccessionFields

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class UpdateAccessionRequestPayload(
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val cutTestSeedsCompromised: Int? = null,
    override val cutTestSeedsEmpty: Int? = null,
    override val cutTestSeedsFilled: Int? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val dryingStartDate: LocalDate? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    @Valid override val germinationTests: List<GerminationTestPayload>? = null,
    override val landowner: String? = null,
    override val numberOfTrees: Int? = null,
    override val nurseryStartDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val seedsCounted: Int? = null,
    override val siteLocation: String? = null,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
    override val storageLocation: String? = null,
    override val storageNotes: String? = null,
    override val storagePackets: Int? = null,
    override val storageStaffResponsible: String? = null,
    override val storageStartDate: LocalDate? = null,
    override val subsetCount: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val totalWeightGrams: BigDecimal? = null,
    @Valid override val withdrawals: List<WithdrawalPayload>? = null,
) : AccessionFields

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccessionPayload(
    @Schema(description = "Server-generated unique identifier for the accession.")
    override val accessionNumber: String,
    @Schema(
        description = "Server-calculated active indicator. This is based on the accession's state.")
    override val active: AccessionActive,
    override val bagNumbers: Set<String>? = null,
    override val collectedDate: LocalDate? = null,
    override val deviceInfo: DeviceInfoPayload? = null,
    override val cutTestSeedsCompromised: Int? = null,
    override val cutTestSeedsEmpty: Int? = null,
    override val cutTestSeedsFilled: Int? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val dryingStartDate: LocalDate? = null,
    @Schema(
        description =
            "Server-calculated effective seed count. This is the exact seed count if available, " +
                "otherwise the estimated seed count, or null if neither seed count is available.")
    override val effectiveSeedCount: Int? = null,
    override val endangered: SpeciesEndangeredType? = null,
    override val environmentalNotes: String? = null,
    override val estimatedSeedCount: Int? = null,
    override val family: String? = null,
    override val fieldNotes: String? = null,
    override val founderId: String? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTests: List<GerminationTestPayload>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val landowner: String? = null,
    override val latestGerminationTestDate: LocalDate? = null,
    override val latestViabilityPercent: Int? = null,
    override val numberOfTrees: Int? = null,
    override val nurseryStartDate: LocalDate? = null,
    override val photoFilenames: List<String>? = null,
    override val primaryCollector: String? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val rare: SpeciesRareType? = null,
    override val receivedDate: LocalDate? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val seedsCounted: Int? = null,
    @Schema(
        description =
            "Number of seeds remaining for withdrawal and testing. Calculated by the server when " +
                "the accession's seed count (actual or estimated) is known.")
    override val seedsRemaining: Int? = null,
    override val siteLocation: String? = null,
    @Schema(
        description =
            "Which application this accession originally came from. This is currently based on " +
                "the presence of the deviceInfo field.")
    override val source: AccessionSource,
    override val sourcePlantOrigin: SourcePlantOrigin? = null,
    override val species: String? = null,
    @Schema(description = "Server-generated unique ID of the species.")
    override val speciesId: Long? = null,
    @Schema(
        description =
            "Server-calculated accession state. Can change due to modifications to accession data " +
                "or based on passage of time.")
    override val state: AccessionState,
    override val storageCondition: StorageCondition? = null,
    override val storageLocation: String? = null,
    override val storagePackets: Int? = null,
    override val storageNotes: String? = null,
    override val storageStaffResponsible: String? = null,
    override val storageStartDate: LocalDate? = null,
    override val subsetCount: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val totalViabilityPercent: Int? = null,
    override val totalWeightGrams: BigDecimal? = null,
    override val withdrawals: List<WithdrawalPayload>? = null,
) : ConcreteAccession {
  constructor(
      model: ConcreteAccession
  ) : this(
      model.accessionNumber,
      model.active,
      model.bagNumbers,
      model.collectedDate,
      model.deviceInfo?.let { DeviceInfoPayload(it) },
      model.cutTestSeedsCompromised,
      model.cutTestSeedsEmpty,
      model.cutTestSeedsFilled,
      model.dryingEndDate,
      model.dryingMoveDate,
      model.dryingStartDate,
      model.effectiveSeedCount,
      model.endangered,
      model.environmentalNotes,
      model.estimatedSeedCount,
      model.family,
      model.fieldNotes,
      model.founderId,
      model.geolocations,
      model.germinationTests?.map { GerminationTestPayload(it) },
      model.germinationTestTypes,
      model.landowner,
      model.latestGerminationTestDate,
      model.latestViabilityPercent,
      model.numberOfTrees,
      model.nurseryStartDate,
      model.photoFilenames,
      model.primaryCollector,
      model.processingMethod,
      model.processingNotes,
      model.processingStaffResponsible,
      model.processingStartDate,
      model.rare,
      model.receivedDate,
      model.secondaryCollectors,
      model.seedsCounted,
      model.seedsRemaining,
      model.siteLocation,
      model.source,
      model.sourcePlantOrigin,
      model.species,
      model.speciesId,
      model.state,
      model.storageCondition,
      model.storageLocation,
      model.storagePackets,
      model.storageNotes,
      model.storageStaffResponsible,
      model.storageStartDate,
      model.subsetCount,
      model.subsetWeightGrams,
      model.targetStorageCondition,
      model.totalViabilityPercent,
      model.totalWeightGrams,
      model.withdrawals?.map { WithdrawalPayload(it) },
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GerminationTestPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this germination test. Null when creating a new test.",
        type = "string")
    @JsonSerialize(using = ToStringSerializer::class)
    override val id: Long? = null,
    @Schema(
        description = "Which type of test is described. At most one of each test type is allowed.")
    override val testType: GerminationTestType,
    override val startDate: LocalDate? = null,
    override val endDate: LocalDate? = null,
    override val seedType: GerminationSeedType? = null,
    override val substrate: GerminationSubstrate? = null,
    override val treatment: GerminationTreatment? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    override val seedsSown: Int? = null,
    override val totalPercentGerminated: Int? = null,
    override val totalSeedsGerminated: Int? = null,
    @Valid override val germinations: List<GerminationPayload>? = null
) : GerminationTestFields {
  constructor(
      model: GerminationTestFields
  ) : this(
      model.id,
      model.testType,
      model.startDate,
      model.endDate,
      model.seedType,
      model.substrate,
      model.treatment,
      model.notes,
      model.staffResponsible,
      model.seedsSown,
      model.totalPercentGerminated,
      model.totalSeedsGerminated,
      model.germinations?.map { GerminationPayload(it) })
}

data class GerminationPayload(
    override val recordingDate: LocalDate,
    @JsonProperty(required = true) override val seedsGerminated: Int
) : GerminationFields {
  constructor(model: GerminationFields) : this(model.recordingDate, model.seedsGerminated)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class WithdrawalPayload(
    @Schema(
        description =
            "Server-assigned unique ID of this withdrawal, its ID. Omit when creating a new " +
                "withdrawal.")
    override val id: Long? = null,
    override val date: LocalDate,
    override val purpose: WithdrawalPurpose,
    @Schema(
        description =
            "Number of seeds withdrawn. If gramsWithdrawn is specified, this is a " +
                "server-calculated estimate based on seed weight and is ignored (and may be " +
                "omitted) when creating a new withdrawal.")
    override val seedsWithdrawn: Int? = null,
    @Schema(
        description =
            "If the withdrawal was measured by weight, its weight in grams. Null if the " +
                "withdrawal has an exact seed count. If this is non-null, seedsWithdrawn is a " +
                "server-calculated estimate. Weight-based withdrawals are only allowed for " +
                "accessions whose seed counts were estimated by weight.")
    override val gramsWithdrawn: BigDecimal? = null,
    override val destination: String? = null,
    override val notes: String? = null,
    override val staffResponsible: String? = null,
    @Schema(
        description =
            "If this withdrawal is of type \"Germination Testing\", the ID of the test it is " +
                "associated with. This is always set by the server and cannot be modified.")
    override val germinationTestId: Long? = null,
) : WithdrawalFields {
  constructor(
      model: WithdrawalFields
  ) : this(
      model.id,
      model.date,
      model.purpose,
      model.seedsWithdrawn,
      model.gramsWithdrawn,
      model.destination,
      model.notes,
      model.staffResponsible,
      model.germinationTestId,
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Details about the device and the application that created the accession. All these " +
            "values are optional and most of them are platform-dependent.")
data class DeviceInfoPayload(
    @Schema(
        description =
            "Build number of application that is submitting the accession, e.g., from React Native getBuildId()")
    override val appBuild: String? = null,
    @Schema(description = "Name of application", example = "Seed Collector")
    override val appName: String? = null,
    @Schema(
        description = "Brand of device, e.g., from React Native getBrand().", example = "Samsung")
    override val brand: String? = null,
    @Schema(description = "Model of device hardware, e.g., from React Native getDeviceId().")
    override val model: String? = null,
    @Schema(
        description =
            "Name the user has assigned to the device, e.g., from React Native getDeviceName().",
        example = "Carlos's iPhone")
    override val name: String? = null,
    @Schema(
        description = "Type of operating system, e.g., from React Native getSystemName().",
        example = "Android")
    override val osType: String? = null,
    @Schema(
        description = "Version of operating system, e.g., from React Native getSystemVersion().",
        example = "7.1.1")
    override val osVersion: String? = null,
    @Schema(
        description =
            "Unique identifier of the hardware device, e.g., from React Native getUniqueId().")
    override val uniqueId: String? = null,
) : AppDeviceFields {
  constructor(
      model: AppDeviceFields
  ) : this(
      model.appBuild,
      model.appName,
      model.brand,
      model.model,
      model.name,
      model.osType,
      model.osVersion,
      model.uniqueId)
}

data class CreateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class UpdateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload
