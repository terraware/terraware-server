package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.CohortModuleStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.model.ModuleModel
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.EventType
import com.terraformation.backend.db.accelerator.ModuleId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.i18n.TimeZones
import io.swagger.v3.oas.annotations.Operation
import java.time.InstantSource
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/modules/")
@RestController
class ModulesController(
    private val clock: InstantSource,
    private val cohortModuleStore: CohortModuleStore,
) {
  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(summary = "List modules.")
  fun listModules(
      @RequestParam projectId: ProjectId?,
      @RequestParam participantId: ParticipantId?,
      @RequestParam cohortId: CohortId?,
  ): ListModulesResponsePayload {
    val models =
        cohortModuleStore.fetch(
            projectId = projectId, participantId = participantId, cohortId = cohortId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return ListModulesResponsePayload(models.map { model -> Module(model, today) })
  }

  @ApiResponse200
  @ApiResponse404
  @GetMapping("/{moduleId}")
  @Operation(summary = "Gets one module.")
  fun getModule(
      @RequestParam projectId: ProjectId?,
      @RequestParam participantId: ParticipantId?,
      @RequestParam cohortId: CohortId?,
      @PathVariable moduleId: ModuleId,
  ): GetModuleResponsePayload {
    val model =
        cohortModuleStore
            .fetch(
                projectId = projectId,
                participantId = participantId,
                cohortId = cohortId,
                moduleId = moduleId)
            .firstOrNull() ?: throw ModuleNotFoundException(moduleId)
    val today = LocalDate.ofInstant(clock.instant(), TimeZones.UTC)
    return GetModuleResponsePayload(Module(model, today))
  }
}

data class Module(
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

data class GetModuleResponsePayload(
    val module: Module,
) : SuccessResponsePayload

data class ListModulesResponsePayload(
    val modules: List<Module>,
) : SuccessResponsePayload
