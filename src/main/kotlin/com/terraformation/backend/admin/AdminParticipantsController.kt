package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.accelerator.db.AcceleratorOrganizationStore
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ParticipantHasProjectsException
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.model.ParticipantModel
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.customer.model.ProjectModel
import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.daos.ProjectAcceleratorDetailsDao
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.daos.ProjectsDao
import com.terraformation.backend.log.perClassLogger
import java.net.URI
import org.springframework.beans.propertyeditors.StringTrimmerEditor
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.InitBinder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminParticipantsController(
    private val acceleratorOrganizationStore: AcceleratorOrganizationStore,
    private val cohortStore: CohortStore,
    private val organizationsDao: OrganizationsDao,
    private val participantStore: ParticipantStore,
    private val projectAcceleratorDetailsDao: ProjectAcceleratorDetailsDao,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
    private val projectsDao: ProjectsDao,
    private val projectStore: ProjectStore,
) {
  private val log = perClassLogger()

  @GetMapping("/participants")
  fun listParticipants(model: Model): String {
    val participants = participantStore.findAll()
    val projectIds = participants.flatMap { it.projectIds }
    val projectsById: Map<ProjectId, ProjectModel<ProjectId>> =
        projectsDao
            .fetchById(*projectIds.toTypedArray())
            .map { ExistingProjectModel.of(it) }
            .sortedBy { it.name }
            .associateBy { it.id }
    val organizationIds = projectsById.values.map { it.organizationId }.distinct()
    val organizations = organizationsDao.fetchById(*organizationIds.toTypedArray())
    val organizationsById = organizations.associateBy { it.id!! }

    model.addAttribute("canCreateParticipant", currentUser().canCreateParticipant())
    model.addAttribute("organizationsById", organizationsById)
    model.addAttribute("participants", participants)
    model.addAttribute("projectsById", projectsById)

    return "/admin/listParticipants"
  }

  @GetMapping("/participants/{participantId}")
  fun getParticipant(@PathVariable participantId: ParticipantId, model: Model): String {
    val availableProjects = acceleratorOrganizationStore.fetchWithUnassignedProjects()
    val participant = participantStore.fetchOneById(participantId)
    val projects = projectsDao.fetchById(*participant.projectIds.toTypedArray())
    val projectAcceleratorDetails =
        projectAcceleratorDetailsDao
            .fetchByProjectId(*participant.projectIds.toTypedArray())
            .associateBy { it.projectId!! }
    val organizationsById =
        organizationsDao
            .fetchById(*projects.map { it.organizationId!! }.toTypedArray())
            .associateBy { it.id!! }
    val cohorts = cohortStore.findAll().sortedBy { it.name }

    model.addAttribute("availableProjects", availableProjects)
    model.addAttribute("canDeleteParticipant", currentUser().canDeleteParticipant(participantId))
    model.addAttribute("canUpdateParticipant", currentUser().canUpdateParticipant(participantId))
    model.addAttribute("cohorts", cohorts)
    model.addAttribute("organizationsById", organizationsById)
    model.addAttribute("participant", participant)
    model.addAttribute("projectAcceleratorDetails", projectAcceleratorDetails)
    model.addAttribute("projects", projects)

    return "/admin/participant"
  }

  @PostMapping("/createParticipant")
  fun createParticipant(
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    return try {
      val model = participantStore.create(ParticipantModel.create(name = name))

      redirectAttributes.successMessage = "Participant created."
      redirectToParticipant(model.id)
    } catch (e: Exception) {
      log.error("Failed to create participant", e)
      redirectAttributes.failureMessage = "Failed to create participant: ${e.message}"
      redirectToListParticipants()
    }
  }

  @PostMapping("/deleteParticipant")
  fun deleteParticipant(
      @RequestParam participantId: ParticipantId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      participantStore.delete(participantId)
      redirectAttributes.successMessage = "Participant deleted."
    } catch (e: ParticipantHasProjectsException) {
      log.warn("Participant $participantId has projects but user was able to try deleting it")
      redirectAttributes.failureMessage = "Participant has projects; cannot delete."
    } catch (e: Exception) {
      log.error("Failed to delete participant $participantId", e)
      redirectAttributes.failureMessage = "Failed to delete participant: ${e.message}"
    }

    return redirectToListParticipants()
  }

  @PostMapping("/updateParticipant")
  fun updateParticipant(
      @RequestParam cohortId: String?,
      @RequestParam participantId: ParticipantId,
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    val cohortIdWrapper = cohortId?.ifBlank { null }?.let { CohortId(it) }

    try {
      participantStore.update(participantId) { it.copy(cohortId = cohortIdWrapper, name = name) }
      redirectAttributes.successMessage = "Participant updated."
    } catch (e: Exception) {
      log.error("Failed to update participant $participantId", e)
      redirectAttributes.failureMessage = "Failed to update participant: ${e.message}"
    }

    return redirectToParticipant(participantId)
  }

  @PostMapping("/addParticipantProject")
  fun addParticipantProject(
      @RequestParam participantId: ParticipantId,
      @RequestParam projectId: ProjectId,
      @RequestParam fileNaming: String,
      @RequestParam googleFolderUrl: URI,
      @RequestParam dropboxFolderPath: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectStore.updateParticipant(projectId, participantId)
      projectAcceleratorDetailsService.update(projectId) {
        it.copy(
            dropboxFolderPath = dropboxFolderPath,
            fileNaming = fileNaming,
            googleFolderUrl = googleFolderUrl,
        )
      }

      redirectAttributes.successMessage = "Added project to participant."
    } catch (e: Exception) {
      log.error("Failed to add project $projectId to participant $participantId", e)
      redirectAttributes.failureMessage = "Failed to add project: ${e.message}"
    }

    return redirectToParticipant(participantId)
  }

  @PostMapping("/deleteParticipantProject")
  fun deleteParticipantProject(
      @RequestParam participantId: ParticipantId,
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectStore.updateParticipant(projectId, null)
      redirectAttributes.successMessage = "Removed project from participant."
    } catch (e: Exception) {
      log.error("Failed to clear participant on project $projectId", e)
      redirectAttributes.failureMessage = "Failed to remove project: ${e.message}"
    }

    return redirectToParticipant(participantId)
  }

  @PostMapping("/updateProjectDocumentSettings")
  fun updateProjectDocumentSettings(
      @RequestParam participantId: ParticipantId,
      @RequestParam projectId: ProjectId,
      @RequestParam fileNaming: String,
      @RequestParam googleFolderUrl: URI,
      @RequestParam dropboxFolderPath: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectAcceleratorDetailsService.update(projectId) {
        it.copy(
            dropboxFolderPath = dropboxFolderPath,
            fileNaming = fileNaming,
            googleFolderUrl = googleFolderUrl,
        )
      }
      redirectAttributes.successMessage = "Updated project settings."
    } catch (e: Exception) {
      log.error("Failed to update settings for project $projectId", e)
      redirectAttributes.failureMessage = "Failed to update project: ${e.message}"
    }

    return redirectToParticipant(participantId)
  }

  @InitBinder
  fun initBinder(binder: WebDataBinder) {
    binder.registerCustomEditor(String::class.java, StringTrimmerEditor(true))
    binder.registerCustomEditor(FacilityId::class.java, StringTrimmerEditor(true))
    binder.registerCustomEditor(UserId::class.java, StringTrimmerEditor(true))
  }

  // Convenience methods to redirect to the GET endpoint for each kind of thing.
  private fun redirectToAdminHome() = "redirect:/admin/"

  private fun redirectToListParticipants() = "redirect:/admin/participants"

  private fun redirectToParticipant(participantId: ParticipantId) =
      "redirect:/admin/participants/$participantId"
}
