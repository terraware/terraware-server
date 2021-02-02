package com.terraformation.seedbank.api.seedbank

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.seedbank.api.NotFoundException
import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import com.terraformation.seedbank.api.SuccessResponsePayload
import com.terraformation.seedbank.api.annotation.ApiResponse404
import com.terraformation.seedbank.api.annotation.ApiResponseSimpleSuccess
import com.terraformation.seedbank.api.annotation.SeedBankAppEndpoint
import com.terraformation.seedbank.db.AccessionFetcher
import com.terraformation.seedbank.db.AccessionState
import com.terraformation.seedbank.db.ProcessingMethod
import com.terraformation.seedbank.db.StorageCondition
import com.terraformation.seedbank.model.AccessionFields
import com.terraformation.seedbank.model.ConcreteAccession
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import java.math.BigDecimal
import java.time.LocalDate
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

  @ApiResponse404(description = "The specified accession doesn't exist.")
  @ApiResponseSimpleSuccess
  @Operation(summary = "Update an existing accession.")
  @PutMapping("/{accessionNumber}")
  fun update(
      @RequestBody payload: UpdateAccessionRequestPayload,
      @PathVariable accessionNumber: String
  ): SimpleSuccessResponsePayload {
    if (!accessionFetcher.update(accessionNumber, payload)) {
      throw NotFoundException()
    } else {
      return SimpleSuccessResponsePayload()
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
) : AccessionFields

@JsonInclude(JsonInclude.Include.NON_NULL)
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
    override val photoFilenames: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
) : AccessionFields

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AccessionPayload(
    override val accessionNumber: String,
    override val state: AccessionState,
    override val status: String, // TODO: AccessionStatus enum
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
    override val photoFilenames: Set<String>? = null,
    override val geolocations: Set<Geolocation>? = null,
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
      model.photoFilenames,
      model.geolocations)
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
        other.latitude.compareTo(latitude) == 0 &&
        other.longitude.compareTo(longitude) == 0 &&
        (other.accuracy == null && accuracy == null ||
            other.accuracy != null && accuracy != null && other.accuracy.compareTo(accuracy) == 0)
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

data class CreateAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload

data class GetAccessionResponsePayload(val accession: AccessionPayload) : SuccessResponsePayload
