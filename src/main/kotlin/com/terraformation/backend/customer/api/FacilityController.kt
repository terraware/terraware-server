package com.terraformation.backend.customer.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.ApiResponseSimpleSuccess
import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.NotFoundException
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.AutomationStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.SiteId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/facility")
class FacilityController(
    private val automationStore: AutomationStore,
    private val facilityStore: FacilityStore
) {
  @GetMapping
  @Operation(
      summary = "List all accessible facilities",
      description = "List all the facilities the current user can access.",
  )
  fun listAllFacilities(): ListFacilitiesResponse {
    val user = currentUser()
    val facilityRoles = user.facilityRoles
    val facilities = facilityStore.fetchAll()

    val elements =
        facilities.map { facility ->
          val role =
              facilityRoles[facility.id]
                  ?: throw IllegalStateException(
                      "BUG! No role for facility that was selected based on the presence of a role")
          FacilityPayload(facility.id, facility.siteId, facility.name, facility.type, role.name)
        }

    return ListFacilitiesResponse(elements)
  }

  @GetMapping("/{facilityId}")
  @Operation(summary = "Gets information about a single facility")
  fun getFacility(@PathVariable facilityId: FacilityId): GetFacilityResponse {
    val facility = facilityStore.fetchById(facilityId) ?: throw NotFoundException()
    val role =
        currentUser().facilityRoles[facilityId]
            ?: throw IllegalStateException("BUG! No role for facility")

    return GetFacilityResponse(
        FacilityPayload(facilityId, facility.siteId, facility.name, facility.type, role.name))
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404("The facility does not exist or is not accessible.")
  @GetMapping("/{facilityId}/automations")
  @Operation(summary = "Lists a facility's automations.")
  fun listAutomations(@PathVariable facilityId: FacilityId): ListAutomationsResponsePayload {
    val automations = automationStore.fetchByFacilityId(facilityId)

    return ListAutomationsResponsePayload(automations.map { AutomationPayload(it) })
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404("The facility does not exist or is not accessible.")
  @PostMapping("/{facilityId}/automations")
  @Operation(summary = "Creates a new automation at a facility.")
  fun createAutomation(
      @PathVariable facilityId: FacilityId,
      @RequestBody payload: ModifyAutomationRequestPayload
  ): CreateAutomationResponsePayload {
    val automationId =
        automationStore.create(facilityId, payload.name, payload.description, payload.configuration)
    return CreateAutomationResponsePayload(automationId)
  }

  @ApiResponse(responseCode = "200", description = "Success")
  @ApiResponse404
  @GetMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Gets information about a single automation.")
  fun getAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId
  ): GetAutomationResponsePayload {
    val model = automationStore.fetchOneById(automationId)
    if (model.facilityId != facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    return GetAutomationResponsePayload(AutomationPayload(model))
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @PutMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Updates an existing automation.")
  fun updateAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId,
      @RequestBody payload: ModifyAutomationRequestPayload
  ): SimpleSuccessResponsePayload {
    val model = automationStore.fetchOneById(automationId)
    if (model.facilityId != facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    automationStore.update(
        model.copy(
            configuration = payload.configuration,
            description = payload.description,
            name = payload.name))

    return SimpleSuccessResponsePayload()
  }

  @ApiResponseSimpleSuccess
  @ApiResponse404
  @DeleteMapping("/{facilityId}/automations/{automationId}")
  @Operation(summary = "Deletes an automation from a facility.")
  fun deleteAutomation(
      @PathVariable facilityId: FacilityId,
      @PathVariable automationId: AutomationId
  ): SimpleSuccessResponsePayload {
    if (facilityId != automationStore.fetchOneById(automationId).facilityId) {
      throw AutomationNotFoundException(automationId)
    }

    automationStore.delete(automationId)

    return SimpleSuccessResponsePayload()
  }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AutomationPayload(
    val id: AutomationId,
    val facilityId: FacilityId,
    @Schema(
        description = "Short human-readable name of this automation.",
    )
    val name: String,
    @Schema(description = "Human-readable description of this automation.")
    val description: String?,
    @Schema(description = "Client-defined configuration data for this automation.")
    val configuration: Map<String, Any?>?,
) {
  constructor(
      model: AutomationModel
  ) : this(model.id, model.facilityId, model.name, model.description, model.configuration)
}

data class FacilityPayload(
    val id: FacilityId,
    val siteId: SiteId,
    val name: String,
    val type: FacilityType,
    @Schema(description = "The name of the role the current user has at the facility.")
    val role: String,
)

data class ModifyAutomationRequestPayload(
    val name: String,
    val description: String?,
    val configuration: Map<String, Any?>?,
)

data class CreateAutomationResponsePayload(val id: AutomationId) : SuccessResponsePayload

data class GetAutomationResponsePayload(val automation: AutomationPayload) : SuccessResponsePayload

data class GetFacilityResponse(val facility: FacilityPayload) : SuccessResponsePayload

data class ListAutomationsResponsePayload(val automations: List<AutomationPayload>) :
    SuccessResponsePayload

data class ListFacilitiesResponse(val facilities: List<FacilityPayload>) : SuccessResponsePayload
