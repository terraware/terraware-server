package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.OrganizationService
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.mapbox.MapboxService
import jakarta.validation.constraints.NotBlank
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
class AdminOrganizationsController(
    private val config: TerrawareServerConfig,
    private val facilityStore: FacilityStore,
    private val mapboxService: MapboxService,
    private val organizationService: OrganizationService,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val seedFundReportStore: SeedFundReportStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()

  @GetMapping("/organization/{organizationId}")
  fun getOrganization(@PathVariable organizationId: OrganizationId, model: Model): String {
    val organization = organizationStore.fetchOneById(organizationId)
    val facilities = facilityStore.fetchByOrganizationId(organizationId)
    val plantingSites = plantingSiteStore.fetchSitesByOrganizationId(organizationId)
    val reports = seedFundReportStore.fetchMetadataByOrganization(organizationId)
    val isSuperAdmin = GlobalRole.SuperAdmin in currentUser().globalRoles

    model.addAttribute("canCreateFacility", currentUser().canCreateFacility(organization.id))
    model.addAttribute(
        "canCreatePlantingSite", currentUser().canCreatePlantingSite(organization.id))
    model.addAttribute("canCreateReport", isSuperAdmin)
    model.addAttribute("canDeleteReport", isSuperAdmin)
    model.addAttribute("canExportReport", isSuperAdmin && config.report.exportEnabled)
    model.addAttribute("facilities", facilities)
    model.addAttribute("facilityTypes", FacilityType.entries)
    model.addAttribute("mapboxToken", mapboxService.generateTemporaryToken())
    model.addAttribute("organization", organization)
    model.addAttribute("plantingSites", plantingSites)
    model.addAttribute("reports", reports)

    return "/admin/organization"
  }

  @PostMapping("/addOrganizationUser")
  fun addOrganizationUser(
      @RequestParam organizationId: OrganizationId,
      @NotBlank @RequestParam email: String,
      @RequestParam role: Role,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      if (role == Role.TerraformationContact) {
        val userId = organizationService.assignTerraformationContact(email, organizationId)
        redirectAttributes.successMessage =
            "User $userId assigned as contact for organization $organizationId"
      } else {
        if (userStore.fetchByEmail(email) != null) {
          val userId = organizationService.addUser(email, organizationId, role)
          redirectAttributes.successMessage = "User $userId added to organization $organizationId"
        } else {
          redirectAttributes.failureMessage = "User $email does not exist"
        }
      }
    } catch (e: Exception) {
      log.warn("Failed to add user to organization", e)
      redirectAttributes.failureMessage = "Adding user failed: ${e.message}"
    }

    return redirectToAdminHome()
  }

  @PostMapping("/removeOrganizationUser")
  fun removeOrganizationUser(
      @RequestParam organizationId: OrganizationId,
      @NotBlank @RequestParam email: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val user = userStore.fetchByEmail(email)
      if (user != null) {
        organizationStore.removeUser(organizationId, user.userId)
      } else {
        redirectAttributes.failureMessage = "User $email does not exist"
      }
    } catch (e: Exception) {
      log.warn("Failed to remove user from organization", e)
      redirectAttributes.failureMessage = "Removing user failed: ${e.message}"
    }

    return redirectToAdminHome()
  }

  private fun redirectToAdminHome() = "redirect:/admin/"
}
