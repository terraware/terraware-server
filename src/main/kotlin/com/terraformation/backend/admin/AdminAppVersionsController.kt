package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.AppVersionStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.tables.pojos.AppVersionsRow
import com.terraformation.backend.log.perClassLogger
import org.springframework.dao.DuplicateKeyException
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
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminAppVersionsController(
    private val appVersionStore: AppVersionStore,
) {
  private val log = perClassLogger()

  @GetMapping("/appVersions")
  fun getAppVersions(model: Model, redirectAttributes: RedirectAttributes): String {
    requirePermissions { updateAppVersions() }

    val versions = appVersionStore.findAll()
    model.addAttribute("appVersions", versions)

    return "/admin/appVersions"
  }

  @PostMapping("/createAppVersion")
  fun createAppVersion(
      @RequestParam appName: String,
      @RequestParam platform: String,
      @RequestParam minimumVersion: String,
      @RequestParam recommendedVersion: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      appVersionStore.create(AppVersionsRow(appName, platform, minimumVersion, recommendedVersion))
      redirectAttributes.successMessage = "App version created."
    } catch (e: DuplicateKeyException) {
      redirectAttributes.failureMessage = "An entry for that app name and platform already exists."
    } catch (e: Exception) {
      log.error("Failed to create app version", e)
      redirectAttributes.failureMessage = "Create failed: ${e.message}"
    }

    return redirectToAppVersions()
  }

  @PostMapping("/updateAppVersion")
  fun updateAppVersion(
      @RequestParam originalAppName: String,
      @RequestParam originalPlatform: String,
      @RequestParam appName: String,
      @RequestParam platform: String,
      @RequestParam minimumVersion: String,
      @RequestParam recommendedVersion: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      appVersionStore.update(
          AppVersionsRow(originalAppName, originalPlatform),
          AppVersionsRow(appName, platform, minimumVersion, recommendedVersion),
      )
      redirectAttributes.successMessage = "App version updated."
    } catch (e: DuplicateKeyException) {
      // User edited the appName/platform to collide with an existing one.
      redirectAttributes.failureMessage = "An entry for that app name and platform already exists."
    } catch (e: Exception) {
      log.error("Failed to update app version", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return redirectToAppVersions()
  }

  @PostMapping("/deleteAppVersion")
  fun deleteAppVersion(
      @RequestParam appName: String,
      @RequestParam platform: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    appVersionStore.delete(appName, platform)
    redirectAttributes.successMessage = "App version deleted."
    return redirectToAppVersions()
  }

  private fun redirectToAppVersions() = "redirect:/admin/appVersions"
}
