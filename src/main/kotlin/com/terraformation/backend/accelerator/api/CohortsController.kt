package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.ExistingCohortModel
import com.terraformation.backend.accelerator.model.NewCohortModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.CohortPhase
import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.util.orNull
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/cohorts")
@RestController
class CohortsController(private val cohortStore: CohortStore) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{cohortId}")
  @Operation(summary = "Gets information about a single cohort.")
  fun getCohort(
      @PathVariable("cohortId") cohortId: CohortId,
      @Parameter(
          description =
              "If specified, retrieve associated entities to the supplied depth. For example, " +
                  "'participant' depth will return the participants associated to the cohort.")
      @RequestParam
      depth: CohortDepth = CohortDepth.Cohort
  ): CohortResponsePayload {
    val cohort = cohortStore.fetchOneById(cohortId, depth)
    return CohortResponsePayload(CohortPayload(cohort))
  }

  @ApiResponse200
  @GetMapping
  @Operation(summary = "Gets the list of cohorts.")
  fun listCohorts(
      @Parameter(
          description =
              "If specified, retrieve associated entities to the supplied depth. For example, " +
                  "'participant' depth will return the participants associated to the cohort.")
      @RequestParam
      depth: CohortDepth = CohortDepth.Cohort
  ): CohortListResponsePayload {
    val cohortList = cohortStore.findAll(depth)
    return CohortListResponsePayload(cohortList.map { CohortPayload(it) })
  }

  @ApiResponse(responseCode = "200", description = "The cohort was created successfully.")
  @PostMapping
  @Operation(summary = "Creates a new cohort.")
  fun createCohort(@RequestBody payload: CreateCohortRequestPayload): CohortResponsePayload {
    val insertedModel = cohortStore.create(payload.toModel())
    return CohortResponsePayload(CohortPayload(insertedModel))
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/{cohortId}")
  @Operation(summary = "Deletes a single cohort.")
  fun deleteCohort(@PathVariable("cohortId") cohortId: CohortId): SimpleSuccessResponsePayload {
    cohortStore.delete(cohortId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping("/{cohortId}")
  @Operation(summary = "Updates the information within a single cohort.")
  fun updateCohort(
      @PathVariable("cohortId") cohortId: CohortId,
      @RequestBody payload: UpdateCohortRequestPayload
  ): CohortResponsePayload {
    cohortStore.update(cohortId, payload::applyChanges)
    return getCohort(cohortId)
  }
}

data class CohortPayload(
    val id: CohortId,
    val name: String,
    val participantIds: Set<ParticipantId>?,
    val phase: CohortPhase
) {
  constructor(
      cohort: ExistingCohortModel
  ) : this(
      id = cohort.id,
      name = cohort.name,
      participantIds = cohort.participantIds.orNull(),
      phase = cohort.phase,
  )
}

data class CreateCohortRequestPayload(
    val name: String,
    val phase: CohortPhase,
) {
  fun toModel() =
      NewCohortModel.create(
          name = name,
          phase = phase,
      )
}

data class UpdateCohortRequestPayload(
    val name: String,
    val phase: CohortPhase,
) {
  fun applyChanges(model: ExistingCohortModel): ExistingCohortModel {
    return model.copy(
        name = name,
        phase = phase,
    )
  }
}

data class CohortResponsePayload(val cohort: CohortPayload) : SuccessResponsePayload

data class CohortListResponsePayload(val cohort: List<CohortPayload>) : SuccessResponsePayload
