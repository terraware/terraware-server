package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionFetcher
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.GerminationSeedType
import com.terraformation.seedbank.db.GerminationSubstrate
import com.terraformation.seedbank.db.GerminationTestType
import com.terraformation.seedbank.db.GerminationTreatment
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.db.WithdrawalPurpose
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.AccessionStatus
import com.terraformation.seedbank.model.ConcreteAccession
import com.terraformation.seedbank.model.GerminationFields
import com.terraformation.seedbank.model.GerminationTestFields
import com.terraformation.seedbank.model.WithdrawalFields
import com.terraformation.seedbank.services.equalsIgnoreScale
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
class AccessionController(private val accessionFetcher: AccessionFetcher) {
  @ApiResponse(
      responseCode = "200",
      description =
          "The accession was created successfully. Response includes fields populated by the " +
              "server, including the accession number.")
  @Operation(summary = "Create a new accession.")
  @PostMapping
  fun create(@RequestBody payload: CreateAccessionRequestPayload): CreateAccessionResponsePayload {
    val updatedPayload = accessionFetcher.create(payload)
    return CreateAccessionResponsePayload(AccessionPayload(updatedPayload))
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
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable accessionNumber: String
  ): UpdateAccessionResponsePayload {
    perClassLogger().info("Payload $payload")
    if (!accessionFetcher.update(accessionNumber, payload)) {
      throw NotFoundException()
    } else {
      val updatedModel = accessionFetcher.fetchByNumber(accessionNumber)!!
      return UpdateAccessionResponsePayload(AccessionPayload(updatedModel))
    }
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @GetMapping("/{accessionNumber}")
  @Operation(summary = "Retrieve an existing accession.")
  fun read(@PathVariable accessionNumber: String): GetAccessionResponsePayload {
    val accession =
        accessionFetcher.fetchByNumber(accessionNumber)
            ?: throw NotFoundException("The specified accession doesn't exist.")
    return GetAccessionResponsePayload(AccessionPayload(accession))
  }
}

data class CreateAccessionRequestPayload(
    override val species: String? = null,
    override val family: String? = null,
    override val numberOfTrees: Int? = null,
    override val founderId: String? = null,
    override val endangered: Boolean? = null,
    override val rare: Boolean? = null,
    override val fieldNotes: String? = null,
    override val collectedDate: LocalDate? = null,
    override val receivedDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val landowner: String? = null,
    override val environmentalNotes: String? = null,
    override val bagNumbers: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val germinationTests: List<GerminationTestPayload>? = null,
) : AccessionFields

data class UpdateAccessionRequestPayload(
    override val species: String? = null,
    override val family: String? = null,
    override val numberOfTrees: Int? = null,
    override val founderId: String? = null,
    override val endangered: Boolean? = null,
    override val rare: Boolean? = null,
    override val fieldNotes: String? = null,
    override val collectedDate: LocalDate? = null,
    override val receivedDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val landowner: String? = null,
    override val environmentalNotes: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val seedsCounted: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val totalWeightGrams: BigDecimal? = null,
    override val subsetCount: Int? = null,
    override val estimatedSeedCount: Int? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val dryingStartDate: LocalDate? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val bagNumbers: Set<String>? = null,
    override val storageStartDate: LocalDate? = null,
    override val storagePackets: Int? = null,
    override val storageLocation: String? = null,
    override val storageNotes: String? = null,
    override val storageStaffResponsible: String? = null,
    override val photoFilenames: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    @Valid override val germinationTests: List<GerminationTestPayload>? = null,
    @Valid override val withdrawals: List<WithdrawalPayload>? = null,
) : AccessionFields

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccessionPayload(
    override val accessionNumber: String,
    override val state: AccessionState,
    override val status: AccessionStatus,
    override val species: String? = null,
    override val family: String? = null,
    override val numberOfTrees: Int? = null,
    override val founderId: String? = null,
    override val endangered: Boolean? = null,
    override val rare: Boolean? = null,
    override val fieldNotes: String? = null,
    override val collectedDate: LocalDate? = null,
    override val receivedDate: LocalDate? = null,
    override val primaryCollector: String? = null,
    override val secondaryCollectors: Set<String>? = null,
    override val siteLocation: String? = null,
    override val landowner: String? = null,
    override val environmentalNotes: String? = null,
    override val processingStartDate: LocalDate? = null,
    override val processingMethod: ProcessingMethod? = null,
    override val seedsCounted: Int? = null,
    override val subsetWeightGrams: BigDecimal? = null,
    override val totalWeightGrams: BigDecimal? = null,
    override val subsetCount: Int? = null,
    override val estimatedSeedCount: Int? = null,
    override val targetStorageCondition: StorageCondition? = null,
    override val dryingStartDate: LocalDate? = null,
    override val dryingEndDate: LocalDate? = null,
    override val dryingMoveDate: LocalDate? = null,
    override val processingNotes: String? = null,
    override val processingStaffResponsible: String? = null,
    override val bagNumbers: Set<String>? = null,
    override val storageStartDate: LocalDate? = null,
    override val storagePackets: Int? = null,
    override val storageLocation: String? = null,
    override val storageCondition: StorageCondition? = null,
    override val storageNotes: String? = null,
    override val storageStaffResponsible: String? = null,
    override val photoFilenames: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
    override val germinationTestTypes: Set<GerminationTestType>? = null,
    override val germinationTests: List<GerminationTestPayload>? = null,
    override val withdrawals: List<WithdrawalPayload>? = null,
) : ConcreteAccession {
  constructor(
      model: ConcreteAccession
  ) : this(
      model.accessionNumber,
      model.state,
      model.status,
      model.species,
      model.family,
      model.numberOfTrees,
      model.founderId,
      model.endangered,
      model.rare,
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
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
class Geolocation(
    val latitude: BigDecimal,
    val longitude: BigDecimal,
    val accuracy: BigDecimal? = null
) {
  /** Tests property values for numeric equality, disregarding differences in decimal scale. */
  override fun equals(other: Any?): Boolean {
    return other is Geolocation &&
        latitude.equalsIgnoreScale(other.latitude) &&
        longitude.equalsIgnoreScale(other.longitude) &&
        accuracy.equalsIgnoreScale(other.accuracy)
  }

  override fun hashCode(): Int {
    return latitude.setScale(10).hashCode() xor
        longitude.setScale(10).hashCode() xor
        (accuracy?.setScale(10)?.hashCode() ?: 13)
  }

  override fun toString() =
      "Geolocation(latitude=${latitude.toPlainString()}, longitude=${longitude.toPlainString()}, " +
          "accuracy=${accuracy?.toPlainString()})"
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
    override val seedType: GerminationSeedType? = null,
    override val substrate: GerminationSubstrate? = null,
    override val treatment: GerminationTreatment? = null,
    override val notes: String? = null,
    override val seedsSown: Int? = null,
    @Valid override val germinations: List<GerminationPayload>? = null
) : GerminationTestFields {
  constructor(
      model: GerminationTestFields
  ) : this(
      model.id,
      model.testType,
      model.startDate,
      model.seedType,
      model.substrate,
      model.treatment,
      model.notes,
      model.seedsSown,
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
    override val staffResponsible: String? = null,
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
      model.staffResponsible)
}

data class CreateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class UpdateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload
