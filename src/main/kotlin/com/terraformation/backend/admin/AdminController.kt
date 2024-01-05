package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import java.util.Locale
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminController(
    private val config: TerrawareServerConfig,
    private val organizationsDao: OrganizationsDao,
    private val organizationStore: OrganizationStore,
) {
  /** Redirects /admin to /admin/ so relative URLs in the UI will work. */
  @GetMapping
  fun redirectToTrailingSlash(): String {
    return "redirect:/admin/"
  }

  @GetMapping("/")
  fun getIndex(model: Model): String {
    val organizations = organizationStore.fetchAll().sortedBy { it.id.value }
    val allOrganizations = organizationsDao.findAll().sortedBy { it.id!!.value }

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute("canAddAnyOrganizationUser", currentUser().canAddAnyOrganizationUser())
    model.addAttribute("canCreateDeviceManager", currentUser().canCreateDeviceManager())
    model.addAttribute("canImportGlobalSpeciesData", currentUser().canImportGlobalSpeciesData())
    model.addAttribute("canManageInternalTags", currentUser().canManageInternalTags())
    model.addAttribute("canManageParticipants", currentUser().canCreateParticipant())
    model.addAttribute("canSetTestClock", config.useTestClock && currentUser().canSetTestClock())
    model.addAttribute("canUpdateAppVersions", currentUser().canUpdateAppVersions())
    model.addAttribute("canUpdateDeviceTemplates", currentUser().canUpdateDeviceTemplates())
    model.addAttribute("canUpdateGlobalRoles", currentUser().canUpdateGlobalRoles())
    model.addAttribute("organizations", organizations)
    model.addAttribute("roles", Role.entries.map { it to it.getDisplayName(Locale.ENGLISH) })

    return "/admin/index"
  }
}
