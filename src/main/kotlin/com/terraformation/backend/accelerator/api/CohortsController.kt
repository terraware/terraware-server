package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.CohortModuleStore
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.model.CohortDepth
import com.terraformation.backend.accelerator.model.ExistingCohortModel
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.accelerator.model.NewCohortModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.i18n.TimeZones
import com.terraformation.backend.util.orNull
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import jakarta.ws.rs.BadRequestException
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
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
class CohortsController(
    private val clock: InstantSource,
    private val cohortModuleStore: CohortModuleStore,
    private val cohortStore: CohortStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{cohortId}")
  @Operation(summary = "Gets information about a single cohort.")
  fun getCohort(
      @PathVariable cohortId: CohortId,
      @Parameter(
          description =
              "If specified, retrieve associated entities to the supplied depth. For example, " +
                  "'participant' depth will return the participants associated to the cohort."
      )
      @RequestParam
      cohortDepth: CohortDepth = CohortDepth.Cohort,
  ): CohortResponsePayload {
    val cohort = cohortStore.fetchOneById(cohortId, cohortDepth)
    return CohortResponsePayload(CohortPayload(cohort))
  }

  @ApiResponse200
  @GetMapping
  @Operation(summary = "Gets the list of cohorts.")
  fun listCohorts(
      @Parameter(
          description =
              "If specified, retrieve associated entities to the supplied depth. For example, " +
                  "'participant' depth will return the participants associated to the cohort."
      )
      @RequestParam
      cohortDepth: CohortDepth = CohortDepth.Cohort,
  ): CohortListResponsePayload {
    val cohortList = cohortStore.findAll(cohortDepth)
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
  fun deleteCohort(@PathVariable cohortId: CohortId): SimpleSuccessResponsePayload {
    cohortStore.delete(cohortId)
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @PutMapping("/{cohortId}")
  @Operation(summary = "Updates the information within a single cohort.")
  fun updateCohort(
      @PathVariable cohortId: CohortId,
      @RequestBody payload: UpdateCohortRequestPayload,
  ): CohortResponsePayload {
    cohortStore.update(cohortId, payload::applyChanges)
    return getCohort(cohortId)
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{cohortId}/modules")
  @Operation(summary = "List cohort modules.")
  fun listCohortModules(
      @PathVariable cohortId: CohortId?,
  ): ListCohortModulesResponsePayload {
    val models = cohortModuleStore.fetch(cohortId = cohortId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return ListCohortModulesResponsePayload(
        models.map { model -> CohortModulePayload(model, today) }
    )
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{cohortId}/modules/{moduleId}")
  @Operation(summary = "Gets one cohort module.")
  fun getCohortModule(
      @PathVariable cohortId: CohortId?,
      @PathVariable moduleId: ModuleId,
  ): GetCohortModuleResponsePayload {
    val model =
        cohortModuleStore.fetch(cohortId = cohortId, moduleId = moduleId).firstOrNull()
            ?: throw ModuleNotFoundException(moduleId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return GetCohortModuleResponsePayload(CohortModulePayload(model, today))
  }

  @ApiResponse200
  @ApiResponse404
  @Operation(
      summary = "Updates the information about a module's use by a cohort.",
      description = "Adds the module to the cohort if it is not already associated.",
  )
  @PutMapping("/{cohortId}/modules/{moduleId}")
  fun updateCohortModule(
      @PathVariable cohortId: CohortId,
      @PathVariable moduleId: ModuleId,
      @RequestBody payload: UpdateCohortModuleRequestPayload,
  ): SimpleSuccessResponsePayload {
    if (payload.endDate < payload.startDate) {
      throw BadRequestException("Start date must be before end date")
    }

    cohortModuleStore.assign(cohortId, moduleId, payload.title, payload.startDate, payload.endDate)

    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @ApiResponse404
  @DeleteMapping("/{cohortId}/modules/{moduleId}")
  @Operation(summary = "Deletes a module from a cohort if it is currently associated.")
  fun deleteCohortModule(
      @PathVariable cohortId: CohortId,
      @PathVariable moduleId: ModuleId,
  ): SimpleSuccessResponsePayload {
    cohortModuleStore.remove(cohortId, moduleId)

    return SimpleSuccessResponsePayload()
  }
}

data class CohortPayload(
    val createdBy: UserId,
    val createdTime: Instant,
    val id: CohortId,
    val modifiedBy: UserId,
    val modifiedTime: Instant,
    val name: String,
    val participantIds: Set<ParticipantId>?,
    val phase: CohortPhase,
) {
  constructor(
      cohort: ExistingCohortModel,
  ) : this(
      createdBy = cohort.createdBy,
      createdTime = cohort.createdTime,
      id = cohort.id,
      modifiedBy = cohort.modifiedBy,
      modifiedTime = cohort.modifiedTime,
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
      NewCohortModel(
          name = name,
          phase = phase,
      )
}

data class UpdateCohortModuleRequestPayload(
    val endDate: LocalDate,
    val startDate: LocalDate,
    val title: String,
)

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

data class CohortModulePayload(
    val id: ModuleId,
    val title: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val isActive: Boolean,
    val additionalResources: String?,
    val overview: String?,
    val preparationMaterials: String?,
    val eventDescriptions: Map<EventType, String>,
) {
  constructor(
      model: ModuleModel,
      today: LocalDate,
  ) : this(
      id = model.id,
      title = model.title!!,
      name = model.name,
      startDate = model.startDate!!,
      endDate = model.endDate!!,
      isActive = today in model.startDate..model.endDate,
      additionalResources = model.additionalResources,
      overview = model.overview,
      preparationMaterials = model.preparationMaterials,
      eventDescriptions = model.eventDescriptions,
  )
}

data class CohortResponsePayload(val cohort: CohortPayload) : SuccessResponsePayload

data class CohortListResponsePayload(val cohorts: List<CohortPayload>) : SuccessResponsePayload

data class GetCohortModuleResponsePayload(
    val module: CohortModulePayload,
) : SuccessResponsePayload

data class ListCohortModulesResponsePayload(
    val modules: List<CohortModulePayload>,
) : SuccessResponsePayload
