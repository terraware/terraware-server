package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.terraformation.backend.api.SeedBankAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.ViabilityTestSeedType
import com.terraformation.backend.db.seedbank.ViabilityTestSubstrate
import com.terraformation.backend.db.seedbank.ViabilityTestType
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.time.LocalDate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v2/seedbank/accessions/{accessionId}/viabilityTests")
@RestController
@SeedBankAppEndpoint
class ViabilityTestsController(
    private val accessionService: AccessionService,
    private val viabilityTestStore: ViabilityTestStore,
) {
  @GetMapping
  @Operation(summary = "List all of the accession's viability tests.")
  fun listViabilityTests(
      @PathVariable("accessionId") accessionId: AccessionId
  ): ListViabilityTestsResponsePayload {
    val tests = viabilityTestStore.fetchViabilityTests(accessionId)
    return ListViabilityTestsResponsePayload(tests.map { GetViabilityTestPayload(it) })
  }

  @GetMapping("/{viabilityTestId}")
  @Operation(summary = "Get a single viability test.")
  fun getViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId,
  ): GetViabilityTestResponsePayload {
    val model = viabilityTestStore.fetchOneById(viabilityTestId)
    if (model.accessionId != accessionId) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    return GetViabilityTestResponsePayload(GetViabilityTestPayload(model))
  }

  @Operation(
      summary = "Create a new viability test on an existing accession.",
      description = "May cause the accession's remaining quantity to change.",
  )
  @PostMapping
  fun createViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @RequestBody payload: CreateViabilityTestRequestPayload,
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.createViabilityTest(payload.toModel(accessionId))
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @DeleteMapping("/{viabilityTestId}")
  @Operation(
      summary = "Delete an existing viability test.",
      description = "May cause the accession's remaining quantity to change.",
  )
  fun deleteViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId,
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.deleteViabilityTest(accessionId, viabilityTestId)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @Operation(
      summary = "Update the details of an existing viability test.",
      description = "May cause the accession's remaining quantity to change.",
  )
  @PutMapping("/{viabilityTestId}")
  fun updateViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId,
      @RequestBody payload: UpdateViabilityTestRequestPayload,
  ): UpdateAccessionResponsePayloadV2 {
    val accession =
        accessionService.updateViabilityTest(accessionId, viabilityTestId, payload::applyToModel)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GetViabilityTestPayload(
    val accessionId: AccessionId,
    val endDate: LocalDate? = null,
    val id: ViabilityTestId,
    val notes: String? = null,
    val seedsCompromised: Int? = null,
    val seedsEmpty: Int? = null,
    val seedsFilled: Int? = null,
    val seedsTested: Int,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testResults: List<ViabilityTestResultPayload>? = null,
    val testType: ViabilityTestType,
    val totalSeedsGerminated: Int? = null,
    val treatment: SeedTreatment? = null,
    @Schema(
        description =
            "Server-calculated viability percent for this test. For lab and nursery tests, this " +
                "is based on the total seeds germinated across all test results. For cut tests, " +
                "it is based on the number of seeds filled."
    )
    val viabilityPercent: Int? = null,
    @Schema(description = "Full name of user who withdrew seeds to perform the test.")
    val withdrawnByName: String? = null,
    @Schema(description = "ID of user who withdrew seeds to perform the test.")
    val withdrawnByUserId: UserId? = null,
) {
  constructor(
      model: ViabilityTestModel
  ) : this(
      accessionId = model.accessionId!!,
      endDate = model.endDate,
      id = model.id!!,
      notes = model.notes,
      seedsCompromised = model.seedsCompromised,
      seedsEmpty = model.seedsEmpty,
      seedsFilled = model.seedsFilled,
      seedsTested = model.seedsTested ?: 1,
      seedType = model.seedType,
      startDate = model.startDate,
      substrate = model.substrate,
      testResults = model.testResults?.map { ViabilityTestResultPayload(it) },
      testType = model.testType,
      totalSeedsGerminated = model.totalSeedsGerminated,
      treatment = model.treatment,
      viabilityPercent = model.viabilityPercent,
      withdrawnByName = model.withdrawnByName,
      withdrawnByUserId = model.withdrawnByUserId,
  )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateViabilityTestRequestPayload(
    val endDate: LocalDate? = null,
    val notes: String? = null,
    val seedsCompromised: Int? = null,
    val seedsEmpty: Int? = null,
    val seedsFilled: Int? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(1) val seedsTested: Int,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    @Valid val testResults: List<ViabilityTestResultPayload>? = null,
    val testType: ViabilityTestType,
    val treatment: SeedTreatment? = null,
    @Schema(
        description =
            "ID of user who withdrew seeds to perform the test. Defaults to the current user. If " +
                "non-null, the current user must have permission to see the referenced user's " +
                "membership details in the organization."
    )
    val withdrawnByUserId: UserId? = null,
) {
  fun toModel(accessionId: AccessionId) =
      ViabilityTestModel(
          accessionId = accessionId,
          endDate = endDate,
          notes = notes,
          seedsCompromised = seedsCompromised,
          seedsEmpty = seedsEmpty,
          seedsFilled = seedsFilled,
          seedsTested = seedsTested,
          seedType = seedType,
          startDate = startDate,
          substrate = substrate,
          testResults = testResults?.map { it.toModel() },
          testType = testType,
          treatment = treatment,
          withdrawnByUserId = withdrawnByUserId,
      )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateViabilityTestRequestPayload(
    val endDate: LocalDate? = null,
    val notes: String? = null,
    val seedsCompromised: Int? = null,
    val seedsEmpty: Int? = null,
    val seedsFilled: Int? = null,
    @JsonSetter(nulls = Nulls.FAIL) @Min(1) val seedsTested: Int,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    @Valid val testResults: List<ViabilityTestResultPayload>? = null,
    val treatment: SeedTreatment? = null,
    @Schema(
        description =
            "ID of user who withdrew seeds to perform the test. If non-null, the current user " +
                "must have permission to see the referenced user's membership details in the " +
                "organization. If absent or null, the existing value is left unchanged."
    )
    val withdrawnByUserId: UserId? = null,
) {
  fun applyToModel(model: ViabilityTestModel): ViabilityTestModel =
      model.copy(
          endDate = endDate,
          notes = notes,
          seedsCompromised = seedsCompromised,
          seedsEmpty = seedsEmpty,
          seedsFilled = seedsFilled,
          seedsTested = seedsTested,
          seedType = seedType,
          startDate = startDate,
          substrate = substrate,
          testResults = testResults?.map { it.toModel() },
          treatment = treatment,
          withdrawnByUserId = withdrawnByUserId ?: model.withdrawnByUserId,
      )
}

data class GetViabilityTestResponsePayload(val viabilityTest: GetViabilityTestPayload) :
    SuccessResponsePayload

data class ListViabilityTestsResponsePayload(val viabilityTests: List<GetViabilityTestPayload>) :
    SuccessResponsePayload
