package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.model.NewFacilityModel
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.email.EmailService
import com.terraformation.backend.log.perClassLogger
import jakarta.validation.constraints.NotBlank
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminFacilitiesController(
    private val config: TerrawareServerConfig,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceStore: DeviceStore,
    private val facilityStore: FacilityStore,
    private val organizationStore: OrganizationStore,
    private val publisher: ApplicationEventPublisher,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @GetMapping("/facility/{facilityId}")
  fun getFacility(@PathVariable facilityId: FacilityId, model: Model): String {
    val facility = facilityStore.fetchOneById(facilityId)
    val organization = organizationStore.fetchOneById(facility.organizationId)
    val recipients =
        userStore
            .fetchByOrganizationId(
                facility.organizationId,
                roles = EmailService.defaultOrgRolesForNotification,
            )
            .map { it.email }
    val subLocations = facilityStore.fetchSubLocations(facilityId)
    val deviceManager = deviceManagerStore.fetchOneByFacilityId(facilityId)
    val devices = deviceStore.fetchByFacilityId(facilityId)

    model.addAttribute("canUpdateFacility", currentUser().canUpdateFacility(facilityId))
    model.addAttribute("connectionStates", FacilityConnectionState.entries)
    model.addAttribute("devices", devices)
    model.addAttribute("deviceManager", deviceManager)
    model.addAttribute("facility", facility)
    model.addAttribute("facilityTypes", FacilityType.entries)
    model.addAttribute("organization", organization)
    model.addAttribute("recipients", recipients)
    model.addAttribute("subLocations", subLocations)

    return "/admin/facility"
  }

  @PostMapping("/createFacility")
  fun createFacility(
      @RequestParam organizationId: OrganizationId,
      @NotBlank @RequestParam name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.failureMessage = "Unknown facility type."
      return redirectToOrganization(organizationId)
    }

    facilityStore.create(
        NewFacilityModel(name = name, organizationId = organizationId, type = type)
    )

    redirectAttributes.successMessage = "Facility created."

    return redirectToOrganization(organizationId)
  }

  @PostMapping("/updateFacility")
  fun updateFacility(
      @RequestParam connectionState: FacilityConnectionState,
      @RequestParam description: String?,
      @RequestParam facilityId: FacilityId,
      @RequestParam maxIdleMinutes: Int,
      @RequestParam name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.failureMessage = "Unknown facility type."
      return redirectToFacility(facilityId)
    }

    val existing = facilityStore.fetchOneById(facilityId)
    facilityStore.update(
        existing.copy(
            name = name,
            description = description?.ifEmpty { null },
            type = type,
            maxIdleMinutes = maxIdleMinutes,
        )
    )

    if (connectionState != existing.connectionState) {
      facilityStore.updateConnectionState(facilityId, existing.connectionState, connectionState)
    }

    redirectAttributes.successMessage = "Facility updated."

    return redirectToFacility(facilityId)
  }

  @PostMapping("/sendAlert")
  fun sendAlert(
      @RequestParam facilityId: FacilityId,
      @RequestParam subject: String,
      @RequestParam body: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    if (
        facilityStore.fetchOneById(facilityId).connectionState != FacilityConnectionState.Configured
    ) {
      redirectAttributes.successMessage =
          "Alert received, but facility is not configured so alert would be ignored."
      return redirectToFacility(facilityId)
    }

    try {
      publisher.publishEvent(
          FacilityAlertRequestedEvent(facilityId, subject, body, currentUser().userId)
      )

      redirectAttributes.successMessage =
          if (config.email.enabled) {
            "Alert sent."
          } else {
            "Alert generated and logged, but email sending is currently disabled."
          }
    } catch (e: Exception) {
      log.error("Failed to send alert", e)
      redirectAttributes.failureMessage = "Failed to send alert."
    }

    return redirectToFacility(facilityId)
  }

  @PostMapping("/createSubLocation")
  fun createSubLocation(
      @RequestParam facilityId: FacilityId,
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    facilityStore.createSubLocation(facilityId, name)

    redirectAttributes.successMessage = "Sub-location created."

    return redirectToFacility(facilityId)
  }

  @PostMapping("/updateSubLocation")
  fun updateSubLocation(
      @RequestParam facilityId: FacilityId,
      @RequestParam subLocationId: SubLocationId,
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    facilityStore.updateSubLocation(subLocationId, name)

    redirectAttributes.successMessage = "Sub-location updated."

    return redirectToFacility(facilityId)
  }

  @PostMapping("/deleteSubLocation")
  fun deleteSubLocation(
      @RequestParam facilityId: FacilityId,
      @RequestParam subLocationId: SubLocationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      facilityStore.deleteSubLocation(subLocationId)
      redirectAttributes.successMessage = "Sub-location deleted."
    } catch (e: DataIntegrityViolationException) {
      redirectAttributes.failureMessage = "Sub-location is in use; can't delete it."
    }

    return redirectToFacility(facilityId)
  }

  private fun redirectToOrganization(organizationId: OrganizationId) =
      "redirect:/admin/organization/$organizationId"

  private fun redirectToFacility(facilityId: FacilityId) = "redirect:/admin/facility/$facilityId"
}
