package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.file.GoogleDriveWriter
import java.net.URI
import java.util.Locale
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminController(
    private val config: TerrawareServerConfig,
    private val organizationsDao: OrganizationsDao,
    private val organizationStore: OrganizationStore,
    private val googleDriveWriter: GoogleDriveWriter,
) {
  /** Redirects /admin to /admin/ so relative URLs in the UI will work. */
  @GetMapping
  fun redirectToTrailingSlash(): String {
    return "redirect:/admin/"
  }

  @GetMapping("/")
  fun getIndex(model: Model): String {
    val organizations = organizationStore.fetchAll().sortedBy { it.id }
    val allOrganizations = organizationsDao.findAll().sortedBy { it.id }

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute(
        "canCleanupApplicationDrive",
        config.accelerator.applicationGoogleFolderId != null &&
            GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute("canAddAnyOrganizationUser", currentUser().canAddAnyOrganizationUser())
    model.addAttribute("canCreateDeviceManager", currentUser().canCreateDeviceManager())
    model.addAttribute("canDeleteUsers", currentUser().canDeleteUsers())
    model.addAttribute("canImportGlobalSpeciesData", currentUser().canImportGlobalSpeciesData())
    model.addAttribute("canManageDefaultProjectLeads", currentUser().canManageDefaultProjectLeads())
    model.addAttribute("canManageDisclaimers", currentUser().canManageDisclaimers())
    model.addAttribute("canManageDocumentProducer", currentUser().canManageDocumentProducer())
    model.addAttribute(
        "canManageHubSpot",
        config.hubSpot.enabled && GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute(
        "canManageModules",
        currentUser().canManageModules() || currentUser().canManageDeliverables())
    model.addAttribute("canManageInternalTags", currentUser().canManageInternalTags())
    model.addAttribute("canManageParticipants", currentUser().canCreateParticipant())
    model.addAttribute(
        "canMigrateSimplePlantingSites", GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute(
        "canQueryGeoServer",
        config.geoServer.wfsUrl != null &&
            currentUser()
                .globalRoles
                .intersect(setOf(GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin))
                .isNotEmpty())
    model.addAttribute("canReadCohorts", currentUser().canReadCohorts())
    model.addAttribute(
        "canRecalculateMortalityRates", GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute(
        "canRecalculatePopulations", GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute(
        "canRemoveOrganizationUser", GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute(
        "canSendTestEmail",
        config.email.enabled && GlobalRole.SuperAdmin in currentUser().globalRoles)
    model.addAttribute("canSetTestClock", config.useTestClock && currentUser().canSetTestClock())
    model.addAttribute("canUpdateAppVersions", currentUser().canUpdateAppVersions())
    model.addAttribute("canUpdateDefaultVoters", currentUser().canUpdateDefaultVoters())
    model.addAttribute("canUpdateDeviceTemplates", currentUser().canUpdateDeviceTemplates())
    model.addAttribute("canUpdateGlobalRoles", currentUser().canUpdateGlobalRoles())
    model.addAttribute("organizations", organizations)
    model.addAttribute("roles", Role.entries.map { it to it.getDisplayName(Locale.ENGLISH) })

    return "/admin/index"
  }

  @PostMapping("/google")
  fun lookUpGoogleFile(@RequestParam url: URI, redirectAttributes: RedirectAttributes): String {
    val fileId = googleDriveWriter.getFileIdForFolderUrl(url)
    val driveId = googleDriveWriter.getDriveIdForFile(fileId)

    redirectAttributes.successMessage = "Drive ID is $driveId"

    return redirectToTrailingSlash()
  }
}
