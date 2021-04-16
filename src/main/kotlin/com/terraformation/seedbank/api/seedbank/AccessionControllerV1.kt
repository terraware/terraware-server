package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.AccessionStore
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.SpeciesEndangeredType
import com.terraformation.seedbank.db.SpeciesRareType
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.model.AccessionActive
import com.terraformation.seedbank.model.AccessionSource
import com.terraformation.seedbank.model.ConcreteAccession
import com.terraformation.seedbank.model.Geolocation
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
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/accession")
@RestController
@SeedBankAppEndpoint
class AccessionControllerV1(private val accessionStore: AccessionStore) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number.")
  @Operation(summary = "Create a new accession.")
  @PostMapping
  fun create(
      @RequestBody payload: CreateAccessionRequestPayloadV1
  ): CreateAccessionResponsePayloadV1 {
    val updatedPayload = accessionStore.create(payload.toV2Payload())
    return CreateAccessionResponsePayloadV1(AccessionPayloadV1(updatedPayload))
  }

  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was updated successfully. Response includes fields populated or " +
              "modified by the server as a result of the update.")
  @ApiResponse404(description = "The specified accession doesn't exist.")
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{accessionNumber}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayloadV1,
      @PathVariable accessionNumber: String
  ): UpdateAccessionResponsePayloadV1 {
    perClassLogger().info("Payload $payload")
    if (!accessionStore.update(accessionNumber, payload.toV2Payload())) {
      throw NotFoundException()
    } else {
      val updatedModel = accessionStore.fetchByNumber(accessionNumber)!!
      return UpdateAccessionResponsePayloadV1(AccessionPayloadV1(updatedModel))
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{accessionNumber}")
  @Operation(summary = "Retrieve an existing accession.")
  fun read(@PathVariable accessionNumber: String): GetAccessionResponsePayloadV1 {
    val accession =
        accessionStore.fetchByNumber(accessionNumber)
            ?: throw NotFoundException("The specified accession doesn't exist.")
    return GetAccessionResponsePayloadV1(AccessionPayloadV1(accession))
  }
}

fun Boolean?.toSpeciesEndangeredType() =
    when (this) {
      true -> SpeciesEndangeredType.Yes
      false -> SpeciesEndangeredType.No
      null -> null
    }

fun Boolean?.toSpeciesRareType() =
    when (this) {
      true -> SpeciesRareType.Yes
      false -> SpeciesRareType.No
      null -> null
    }

fun SpeciesEndangeredType?.toBoolean() =
    when (this) {
      SpeciesEndangeredType.Yes -> true
      SpeciesEndangeredType.No -> false
      else -> null
    }

fun SpeciesRareType?.toBoolean() =
    when (this) {
      SpeciesRareType.Yes -> true
      SpeciesRareType.No -> false
      else -> null
    }

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class CreateAccessionRequestPayloadV1(
    val species: String? = null,
    val family: String? = null,
    val numberOfTrees: Int? = null,
    val founderId: String? = null,
    val endangered: Boolean? = null,
    val rare: Boolean? = null,
    val fieldNotes: String? = null,
    val collectedDate: LocalDate? = null,
    val receivedDate: LocalDate? = null,
    val primaryCollector: String? = null,
    val secondaryCollectors: Set<String>? = null,
    val siteLocation: String? = null,
    val landowner: String? = null,
    val environmentalNotes: String? = null,
    val bagNumbers: Set<String>? = null,
    val geolocations: Set<Geolocation>? = null,
    val germinationTestTypes: Set<GerminationTestType>? = null,
    val germinationTests: List<GerminationTestPayload>? = null,
    val deviceInfo: DeviceInfoPayload? = null,
) {
  fun toV2Payload(): CreateAccessionRequestPayload {
    return CreateAccessionRequestPayload(
        bagNumbers,
        collectedDate,
        deviceInfo,
        endangered.toSpeciesEndangeredType(),
        environmentalNotes,
        family,
        fieldNotes,
        founderId,
        geolocations,
        germinationTestTypes,
        germinationTests,
        landowner,
        numberOfTrees,
        primaryCollector,
        rare.toSpeciesRareType(),
        receivedDate,
        secondaryCollectors,
        siteLocation,
        species,
    )
  }
}

// Ignore properties that are defined on AccessionFields but not accepted as input (CU-px8k25)
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
data class UpdateAccessionRequestPayloadV1(
    val species: String? = null,
    val family: String? = null,
    val numberOfTrees: Int? = null,
    val founderId: String? = null,
    val endangered: Boolean? = null,
    val rare: Boolean? = null,
    val fieldNotes: String? = null,
    val collectedDate: LocalDate? = null,
    val receivedDate: LocalDate? = null,
    val primaryCollector: String? = null,
    val secondaryCollectors: Set<String>? = null,
    val siteLocation: String? = null,
    val landowner: String? = null,
    val environmentalNotes: String? = null,
    val processingStartDate: LocalDate? = null,
    val processingMethod: ProcessingMethod? = null,
    val seedsCounted: Int? = null,
    val subsetWeightGrams: BigDecimal? = null,
    val totalWeightGrams: BigDecimal? = null,
    val subsetCount: Int? = null,
    val targetStorageCondition: StorageCondition? = null,
    val dryingStartDate: LocalDate? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val bagNumbers: Set<String>? = null,
    val storageStartDate: LocalDate? = null,
    val storagePackets: Int? = null,
    val storageLocation: String? = null,
    val storageNotes: String? = null,
    val storageStaffResponsible: String? = null,
    val geolocations: Set<Geolocation>? = null,
    val germinationTestTypes: Set<GerminationTestType>? = null,
    val cutTestSeedsCompromised: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsFilled: Int? = null,
    @Valid val germinationTests: List<GerminationTestPayload>? = null,
    @Valid val withdrawals: List<WithdrawalPayload>? = null,
) {
  fun toV2Payload(): UpdateAccessionRequestPayload {
    return UpdateAccessionRequestPayload(
        bagNumbers,
        collectedDate,
        cutTestSeedsCompromised,
        cutTestSeedsEmpty,
        cutTestSeedsFilled,
        dryingEndDate,
        dryingMoveDate,
        dryingStartDate,
        endangered.toSpeciesEndangeredType(),
        environmentalNotes,
        family,
        fieldNotes,
        founderId,
        geolocations,
        germinationTestTypes,
        germinationTests,
        landowner,
        numberOfTrees,
        primaryCollector,
        processingMethod,
        processingNotes,
        processingStaffResponsible,
        processingStartDate,
        rare.toSpeciesRareType(),
        receivedDate,
        secondaryCollectors,
        seedsCounted,
        siteLocation,
        species,
        storageLocation,
        storageNotes,
        storagePackets,
        storageStaffResponsible,
        storageStartDate,
        subsetCount,
        subsetWeightGrams,
        targetStorageCondition,
        totalWeightGrams,
        withdrawals,
    )
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccessionPayloadV1(
    @Schema(description = "Server-generated unique identifier for the accession.")
    val accessionNumber: String,
    @Schema(
        description =
            "Server-calculated accession state. Can change due to modifications to accession data " +
                "or based on passage of time.")
    val state: AccessionState,
    @Schema(
        description = "Server-calculated active indicator. This is based on the accession's state.")
    val active: AccessionActive,
    @Schema(
        description =
            "Which application this accession originally came from. This is currently based on " +
                "the presence of the deviceInfo field.")
    val source: AccessionSource,
    val species: String? = null,
    @Schema(description = "Server-generated unique ID of the species.") val speciesId: Long? = null,
    val family: String? = null,
    val numberOfTrees: Int? = null,
    val founderId: String? = null,
    val endangered: Boolean? = null,
    val rare: Boolean? = null,
    val fieldNotes: String? = null,
    val collectedDate: LocalDate? = null,
    val receivedDate: LocalDate? = null,
    val primaryCollector: String? = null,
    val secondaryCollectors: Set<String>? = null,
    val siteLocation: String? = null,
    val landowner: String? = null,
    val environmentalNotes: String? = null,
    val processingStartDate: LocalDate? = null,
    val processingMethod: ProcessingMethod? = null,
    val seedsCounted: Int? = null,
    val subsetWeightGrams: BigDecimal? = null,
    val totalWeightGrams: BigDecimal? = null,
    val subsetCount: Int? = null,
    val estimatedSeedCount: Int? = null,
    @Schema(
        description =
            "Server-calculated effective seed count. This is the exact seed count if available, " +
                "otherwise the estimated seed count, or null if neither seed count is available.")
    val effectiveSeedCount: Int? = null,
    val targetStorageCondition: StorageCondition? = null,
    val dryingStartDate: LocalDate? = null,
    val dryingEndDate: LocalDate? = null,
    val dryingMoveDate: LocalDate? = null,
    val processingNotes: String? = null,
    val processingStaffResponsible: String? = null,
    val bagNumbers: Set<String>? = null,
    val storageStartDate: LocalDate? = null,
    val storagePackets: Int? = null,
    val storageLocation: String? = null,
    val storageCondition: StorageCondition? = null,
    val storageNotes: String? = null,
    val storageStaffResponsible: String? = null,
    val photoFilenames: List<String>? = null,
    val geolocations: Set<Geolocation>? = null,
    val germinationTestTypes: Set<GerminationTestType>? = null,
    val germinationTests: List<GerminationTestPayload>? = null,
    val withdrawals: List<WithdrawalPayload>? = null,
    val cutTestSeedsFilled: Int? = null,
    val cutTestSeedsEmpty: Int? = null,
    val cutTestSeedsCompromised: Int? = null,
    val latestGerminationTestDate: LocalDate? = null,
    val latestViabilityPercent: Int? = null,
    val totalViabilityPercent: Int? = null,
    val deviceInfo: DeviceInfoPayload? = null,
    @Schema(
        description =
            "Number of seeds remaining for withdrawal and testing. Calculated by the server when " +
                "the accession's seed count (actual or estimated) is known.")
    val seedsRemaining: Int? = null,
) {
  constructor(
      model: ConcreteAccession
  ) : this(
      model.accessionNumber,
      model.state,
      model.active,
      model.source,
      model.species,
      model.speciesId,
      model.family,
      model.numberOfTrees,
      model.founderId,
      model.endangered.toBoolean(),
      model.rare.toBoolean(),
      model.fieldNotes,
      model.collectedDate,
      model.receivedDate,
      model.primaryCollector,
      model.secondaryCollectors,
      model.siteLocation,
      model.landowner,
      model.environmentalNotes,
      model.processingStartDate,
      model.processingMethod,
      model.seedsCounted,
      model.subsetWeightGrams,
      model.totalWeightGrams,
      model.subsetCount,
      model.estimatedSeedCount,
      model.effectiveSeedCount,
      model.targetStorageCondition,
      model.dryingStartDate,
      model.dryingEndDate,
      model.dryingMoveDate,
      model.processingNotes,
      model.processingStaffResponsible,
      model.bagNumbers,
      model.storageStartDate,
      model.storagePackets,
      model.storageLocation,
      model.storageCondition,
      model.storageNotes,
      model.storageStaffResponsible,
      model.photoFilenames,
      model.geolocations,
      model.germinationTestTypes,
      model.germinationTests?.map { GerminationTestPayload(it) },
      model.withdrawals?.map { WithdrawalPayload(it) },
      model.cutTestSeedsFilled,
      model.cutTestSeedsEmpty,
      model.cutTestSeedsCompromised,
      model.latestGerminationTestDate,
      model.latestViabilityPercent,
      model.totalViabilityPercent,
      model.deviceInfo?.let { DeviceInfoPayload(it) },
      model.seedsRemaining,
  )
}

data class CreateAccessionResponsePayloadV1(val accession: AccessionPayloadV1) :
    SuccessResponsePayload

data class UpdateAccessionResponsePayloadV1(val accession: AccessionPayloadV1) :
    SuccessResponsePayload

data class GetAccessionResponsePayloadV1(val accession: AccessionPayloadV1) :
    SuccessResponsePayload
