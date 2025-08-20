package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.AccessionStore
import com.terraformation.backend.seedbank.model.AccessionHistoryModel
import com.terraformation.backend.seedbank.model.AccessionHistoryType
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import com.terraformation.backend.seedbank.model.ViabilityTestResultModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/seedbank/accessions")
@RestController
@SeedBankAppEndpoint
class AccessionsController(
    private val accessionService: AccessionService,
    private val accessionStore: AccessionStore,
) {
  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/{id}")
  @Operation(summary = "Deletes an existing accession.")
  fun delete(@PathVariable("id") accessionId: AccessionId): SimpleSuccessResponsePayload {
    accessionService.deleteAccession(accessionId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse(responseCode = "200")
  @ApiResponse404
  @Operation(summary = "Marks an accession as checked in.")
  @PostMapping("/{id}/checkIn")
  fun checkIn(@PathVariable("id") accessionId: AccessionId): UpdateAccessionResponsePayloadV2 {
    val accession = accessionStore.checkIn(accessionId)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
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

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
    description =
        "Represents a quantity of seeds, measured either in individual seeds or by weight."
)
data class SeedQuantityPayload(
    @Schema(
        description =
            "Number of units of seeds. If \"units\" is \"Seeds\", this is the number of seeds " +
                "and must be an integer. Otherwise it is a measurement in the weight units " +
                "specified in the \"units\" field, and may have a fractional part."
    )
    @PositiveOrZero
    val quantity: BigDecimal,
    val units: SeedQuantityUnits,
    @Schema(
        description =
            "If this quantity is a weight measurement, the weight in grams. This is not set if " +
                "the \"units\" field is \"Seeds\". This is always calculated on the server side " +
                "and is ignored on input.",
        readOnly = true,
    )
    val grams: BigDecimal? = null,
) {
  constructor(model: SeedQuantityModel) : this(model.quantity, model.units, model.grams)

  fun toModel() = SeedQuantityModel(quantity, units)
}

fun SeedQuantityModel.toPayload() = SeedQuantityPayload(this)

data class ViabilityTestResultPayload(
    val recordingDate: LocalDate,
    @JsonProperty(
        required = true,
    )
    val seedsGerminated: Int,
) {
  constructor(model: ViabilityTestResultModel) : this(model.recordingDate, model.seedsGerminated)

  fun toModel() = ViabilityTestResultModel(null, recordingDate, seedsGerminated, null)
}

data class AccessionHistoryEntryPayload(
    val batchId: BatchId?,
    val date: LocalDate,
    @Schema(
        description = "Human-readable description of the event. Does not include date or userName.",
        example = "updated the status to Drying",
    )
    val description: String,
    @Schema(description = "Full name of the person responsible for the event, if known.")
    val fullName: String?,
    @Schema(description = "User-entered notes about the event, if any.") //
    val notes: String?,
    val type: AccessionHistoryType,
) {
  constructor(
      model: AccessionHistoryModel
  ) : this(model.batchId, model.date, model.description, model.fullName, model.notes, model.type)
}

data class GetAccessionHistoryResponsePayload(
    @ArraySchema(
        arraySchema =
            Schema(description = "History of changes in descending time order (newest first.)")
    )
    val history: List<AccessionHistoryEntryPayload>
) : SuccessResponsePayload
