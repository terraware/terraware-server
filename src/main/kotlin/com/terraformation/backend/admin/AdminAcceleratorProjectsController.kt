package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.AcceleratorProjectService
import com.terraformation.backend.accelerator.db.ProjectAcceleratorDetailsStore
import com.terraformation.backend.accelerator.variables.AcceleratorProjectVariableValuesService
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminAcceleratorProjectsController(
    private val acceleratorProjectService: AcceleratorProjectService,
    private val acceleratorProjectVariableValuesService: AcceleratorProjectVariableValuesService,
    private val projectAcceleratorDetailsStore: ProjectAcceleratorDetailsStore,
) {
  private val log = perClassLogger()

  @PostMapping("/acceleratorProjects/migrateVariables")
  fun getAcceleratorProjects(
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { readAllAcceleratorDetails() }

    val projectDetails =
        try {
          val projects = acceleratorProjectService.listAcceleratorProjects()
          projects.map { projectAcceleratorDetailsStore.fetchOneById(it.projectId) }
        } catch (e: Exception) {
          log.error("Failed to fetch accelerator project details:", e)
          redirectAttributes.failureMessage =
              "Failed to fetch accelerator project details: ${e.message}"
          return redirectToHome()
        }

    val problems = mutableListOf<String>()
    log.info("Migrating ${projectDetails.size} project details to variables.")
    projectDetails.forEach { acceleratorDetail ->
      try {
        acceleratorProjectVariableValuesService.writeValues(
            acceleratorDetail.projectId, acceleratorDetail.toVariableValuesModel())
        log.info("Variables migration for project ${acceleratorDetail.projectId} succeeded.")
      } catch (e: Exception) {
        problems.add("Project ${acceleratorDetail.projectId}: ${e.message}")
        log.warn("Migration for project ${acceleratorDetail.projectId} failed", e)
      }
    }

    if (problems.size == 0) {
      redirectAttributes.successMessage =
          "Successfully migrated ${projectDetails.size} project details to variables."
      return redirectToHome()
    } else {
      redirectAttributes.failureMessage =
          "Failed to migrate ${problems.size} project details to variables."
      redirectAttributes.failureDetails = problems
      return redirectToHome()
    }
  }

  private fun redirectToHome() = "redirect:/admin/"
}
