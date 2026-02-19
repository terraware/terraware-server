package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.DeliverableDueDateStore
import com.terraformation.backend.accelerator.db.DeliverableNotFoundException
import com.terraformation.backend.accelerator.db.DeliverableStore
import com.terraformation.backend.accelerator.db.ModuleNotFoundException
import com.terraformation.backend.accelerator.db.ModuleStore
import com.terraformation.backend.accelerator.db.ProjectModuleStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.log.perClassLogger
import java.time.LocalDate
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
@RequestMapping("/admin/acceleratorProjects")
@RequireGlobalRole(
    [GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert, GlobalRole.ReadOnly]
)
@Validated
class AdminAcceleratorProjectsController(
    private val deliverableDueDateStore: DeliverableDueDateStore,
    private val deliverableStore: DeliverableStore,
    private val moduleStore: ModuleStore,
    private val projectModuleStore: ProjectModuleStore,
    private val projectStore: ProjectStore,
) {
  private val log = perClassLogger()

  @GetMapping
  fun acceleratorProjectsHome(model: Model): String {
    val projects = projectStore.findAllWithPhase()

    model.addAttribute("projects", projects)

    return "/admin/acceleratorProjects"
  }

  @GetMapping("/{projectId}")
  fun acceleratorProjectView(model: Model, @PathVariable projectId: ProjectId): String {
    val project = projectStore.fetchOneById(projectId)
    val projectModules = projectModuleStore.fetch(projectId = projectId)

    val modules = moduleStore.fetchAllModules()
    val allDeliverables = deliverableStore.fetchDeliverables().groupBy { it.moduleId }
    val moduleDeliverables = modules.associate { it.id to (allDeliverables[it.id] ?: emptyList()) }

    val moduleNames = modules.associate { it.id to "(${it.id}) ${it.name}" }

    val unassignedModules = modules.filter { module -> projectModules.all { module.id != it.id } }

    model.addAttribute(
        "canUpdateProjectAcceleratorDetails",
        currentUser().canUpdateProjectAcceleratorDetails(projectId),
    )
    model.addAttribute("moduleDeliverables", moduleDeliverables)
    model.addAttribute("moduleNames", moduleNames)
    model.addAttribute("project", project)
    model.addAttribute("projectModules", projectModules)
    model.addAttribute("unassignedModules", unassignedModules)

    return "/admin/acceleratorProjectView"
  }

  @PostMapping("/{projectId}/deliverables")
  fun projectDeliverablesRedirect(
      @PathVariable projectId: ProjectId,
      @RequestParam deliverableId: DeliverableId,
  ): String = redirectToProjectDeliverable(projectId, deliverableId)

  @GetMapping("/{projectId}/deliverables/{deliverableId}")
  fun viewProjectDeliverable(
      model: Model,
      @PathVariable projectId: ProjectId,
      @PathVariable deliverableId: DeliverableId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { readAllDeliverables() }

    try {
      val project = projectStore.fetchOneById(projectId)

      val dueDateModel =
          deliverableDueDateStore
              .fetchDeliverableDueDates(projectId = projectId, deliverableId = deliverableId)
              .firstOrNull()

      if (dueDateModel == null) {
        redirectAttributes.failureMessage =
            "Deliverable $deliverableId not associated with project $projectId"
        return redirectToAcceleratorProject(projectId)
      }

      val moduleModel =
          projectModuleStore
              .fetch(projectId = projectId, moduleId = dueDateModel.moduleId)
              .firstOrNull() ?: throw ModuleNotFoundException(dueDateModel.moduleId)

      val deliverable =
          deliverableStore.fetchDeliverables(deliverableId).firstOrNull()
              ?: throw DeliverableNotFoundException(deliverableId)

      val submission =
          deliverableStore
              .fetchDeliverableSubmissions(deliverableId = deliverableId, projectId = projectId)
              .firstOrNull()
              ?: throw Exception("No submission for project $projectId deliverable $deliverableId")

      model.addAttribute("canManageDeliverables", currentUser().canManageDeliverables())
      model.addAttribute("deliverable", deliverable)
      model.addAttribute("dueDates", dueDateModel)
      model.addAttribute("module", moduleModel)
      model.addAttribute("project", project)
      model.addAttribute("submission", submission)

      return "/admin/acceleratorProjectDeliverable"
    } catch (e: Exception) {
      log.warn("Project deliverable view failed", e)
      redirectAttributes.failureMessage =
          "Error looking up deliverable data for project: ${e.message}"
      return redirectToAcceleratorProject(projectId)
    }
  }

  @PostMapping("/{projectId}/deliverables/{deliverableId}")
  fun updateProjectDeliverableDueDate(
      @PathVariable projectId: ProjectId,
      @PathVariable deliverableId: DeliverableId,
      @RequestParam operation: String,
      @RequestParam dueDate: LocalDate?,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageDeliverables() }
    when (operation) {
      "upsert" -> {
        if (dueDate == null) {
          redirectAttributes.failureMessage = "Due date must be set for upsert"
          return redirectToProjectDeliverable(projectId, deliverableId)
        }

        try {
          deliverableDueDateStore.upsertDeliverableProjectDueDate(
              deliverableId,
              projectId,
              dueDate,
          )
        } catch (e: Exception) {
          log.warn("Upsert deliverable due date failed", e)
          redirectAttributes.failureMessage = "Error updating deliverable due date: ${e.message}"
        }

        redirectAttributes.successMessage = "Successfully updated due date."
      }
      "remove" -> {
        try {
          deliverableDueDateStore.deleteDeliverableProjectDueDate(deliverableId, projectId)
        } catch (e: Exception) {
          log.warn("Delete deliverable due date failed", e)
          redirectAttributes.failureMessage = "Error deleting deliverable due date: ${e.message}"
        }

        redirectAttributes.successMessage = "Successfully deleted due date."
      }
      else -> {
        redirectAttributes.failureMessage = "Operation not recognized. "
      }
    }
    return redirectToProjectDeliverable(projectId, deliverableId)
  }

  private fun redirectToAcceleratorProject(projectId: ProjectId) =
      "redirect:/admin/acceleratorProjects/$projectId"

  private fun redirectToProjectDeliverable(projectId: ProjectId, deliverableId: DeliverableId) =
      "redirect:/admin/acceleratorProjects/$projectId/deliverables/$deliverableId"
}
