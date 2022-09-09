package com.terraformation.backend.seedbank.api

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.ViabilityTestId
import com.terraformation.backend.db.ViabilityTestNotFoundException
import com.terraformation.backend.db.ViabilityTestSeedType
import com.terraformation.backend.db.ViabilityTestSubstrate
import com.terraformation.backend.db.ViabilityTestTreatment
import com.terraformation.backend.db.ViabilityTestType
import com.terraformation.backend.seedbank.AccessionService
import com.terraformation.backend.seedbank.db.ViabilityTestStore
import com.terraformation.backend.seedbank.model.ViabilityTestModel
import io.swagger.v3.oas.annotations.Operation
import java.time.LocalDate
import javax.validation.Valid
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
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId
  ): GetViabilityTestResponsePayload {
    val model = viabilityTestStore.fetchOneById(viabilityTestId)
    if (model.accessionId != accessionId) {
      throw ViabilityTestNotFoundException(viabilityTestId)
    }

    return GetViabilityTestResponsePayload(GetViabilityTestPayload(model))
  }

  @Operation(
      summary = "Create a new viability test on an existing accession.",
      description = "May cause the accession's remaining quantity to change.")
  @PostMapping
  fun createViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @RequestBody payload: CreateViabilityTestRequestPayload
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.createViabilityTest(payload.toModel(accessionId))
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @DeleteMapping("/{viabilityTestId}")
  @Operation(
      summary = "Delete an existing viability test.",
      description = "May cause the accession's remaining quantity to change.")
  fun deleteViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId
  ): UpdateAccessionResponsePayloadV2 {
    val accession = accessionService.deleteViabilityTest(accessionId, viabilityTestId)
    return UpdateAccessionResponsePayloadV2(AccessionPayloadV2(accession))
  }

  @Operation(
      summary = "Update the details of an existing viability test.",
      description = "May cause the accession's remaining quantity to change.")
  @PutMapping("/{viabilityTestId}")
  fun updateViabilityTest(
      @PathVariable("accessionId") accessionId: AccessionId,
      @PathVariable("viabilityTestId") viabilityTestId: ViabilityTestId,
      @RequestBody payload: UpdateViabilityTestRequestPayload
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
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val treatment: ViabilityTestTreatment? = null,
    val testingStaffName: String? = null,
    val testingStaffUserId: UserId? = null,
    val testResults: List<ViabilityTestResultPayload>? = null,
    val testType: ViabilityTestType,
    val totalPercentGerminated: Int? = null,
    val totalSeedsGerminated: Int? = null,
) {
  constructor(
      model: ViabilityTestModel
  ) : this(
      accessionId = model.accessionId!!,
      endDate = model.endDate,
      id = model.id!!,
      notes = model.notes,
      seedsTested = model.seedsSown,
      seedType = model.seedType,
      startDate = model.startDate,
      substrate = model.substrate,
      testingStaffName = model.staffResponsible,
      testingStaffUserId = null,
      testResults = model.testResults?.map { ViabilityTestResultPayload(it) },
      testType = model.testType,
      totalPercentGerminated = model.totalPercentGerminated,
      totalSeedsGerminated = model.totalSeedsGerminated,
      treatment = model.treatment,
  )
}

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateViabilityTestRequestPayload(
    val endDate: LocalDate? = null,
    val notes: String? = null,
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testingStaffUserId: UserId? = null,
    val testType: ViabilityTestType,
    val treatment: ViabilityTestTreatment? = null,
) {
  fun toModel(accessionId: AccessionId) =
      ViabilityTestModel(
          accessionId = accessionId,
          endDate = endDate,
          notes = notes,
          seedsSown = seedsTested,
          seedType = seedType,
          startDate = startDate,
          substrate = substrate,
          testType = testType,
          treatment = treatment,
      )
}

// Mark all fields as write-only in the schema
@JsonAutoDetect(getterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateViabilityTestRequestPayload(
    val endDate: LocalDate? = null,
    val notes: String? = null,
    val seedsTested: Int? = null,
    val seedType: ViabilityTestSeedType? = null,
    val startDate: LocalDate? = null,
    val substrate: ViabilityTestSubstrate? = null,
    val testingStaffUserId: UserId? = null,
    @Valid val testResults: List<ViabilityTestResultPayload>? = null,
    val testType: ViabilityTestType,
    val treatment: ViabilityTestTreatment? = null,
) {
  fun applyToModel(model: ViabilityTestModel): ViabilityTestModel =
      model.copy(
          endDate = endDate,
          notes = notes,
          seedsSown = seedsTested,
          seedType = seedType,
          startDate = startDate,
          substrate = substrate,
          testResults = testResults?.map { it.toModel() },
          testType = testType,
          treatment = treatment,
      )
}

data class GetViabilityTestResponsePayload(val viabilityTest: GetViabilityTestPayload) :
    SuccessResponsePayload

data class ListViabilityTestsResponsePayload(val viabilityTests: List<GetViabilityTestPayload>) :
    SuccessResponsePayload
