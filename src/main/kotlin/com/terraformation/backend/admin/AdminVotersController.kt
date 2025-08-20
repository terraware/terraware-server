package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.AcceleratorProjectService
import com.terraformation.backend.accelerator.db.DefaultVoterStore
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.log.perClassLogger
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
@RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
@Validated
class AdminVotersController(
    private val acceleratorProjectService: AcceleratorProjectService,
    private val defaultVoterStore: DefaultVoterStore,
    private val userStore: UserStore,
    private val voteStore: VoteStore,
) {
  private val log = perClassLogger()

  @GetMapping("/voters")
  fun getVoters(
      @RequestParam email: String?,
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    val userIdForEmail = email?.let { userStore.fetchByEmail(it)?.userId }

    val projects = acceleratorProjectService.listAcceleratorProjects()
    val projectVoters: Map<ProjectId, Set<UserId>> =
        projects.associateBy(
            { it.projectId },
            { project ->
              voteStore.fetchAllVotes(project.projectId, project.phase).map { it.userId }.toSet()
            },
        )

    val defaultVoters = defaultVoterStore.findAll()
    val users =
        userStore
            .fetchManyById(
                defaultVoters.union(
                    projectVoters.values.reduce { acc, userIds -> acc.union(userIds) }
                )
            )
            .associateBy { it.userId }

    model.addAttribute("selectedEmail", email)
    model.addAttribute("selectedUser", userIdForEmail)
    model.addAttribute("defaultVoters", defaultVoters)
    model.addAttribute("projects", projects)
    model.addAttribute("projectVoters", projectVoters)
    model.addAttribute("users", users)

    return "/admin/voters"
  }

  @PostMapping("/voters/add")
  fun addVoter(
      @RequestParam email: String?,
      @RequestParam userId: UserId,
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateProjectVotes(projectId) }

    val projects = acceleratorProjectService.listAcceleratorProjects().associateBy { it.projectId }
    try {
      voteStore.upsert(projectId, projects[projectId]!!.phase, userId)
      redirectAttributes.successMessage = "Voter added."
    } catch (e: Exception) {
      log.error("Failed to add voter $userId to project $projectId", e)
      redirectAttributes.failureMessage = "Failed to add voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  @PostMapping("/voters/remove")
  fun removeVoter(
      @RequestParam email: String?,
      @RequestParam userIds: List<UserId>,
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateProjectVotes(projectId) }

    val projects = acceleratorProjectService.listAcceleratorProjects().associateBy { it.projectId }
    try {
      userIds.forEach { voteStore.delete(projectId, projects[projectId]!!.phase, it) }
      redirectAttributes.successMessage = "Voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove voters from project $projectId", e)
      redirectAttributes.failureMessage = "Failed to remove voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  @PostMapping("/voters/default/add")
  fun addDefaultVoter(
      @RequestParam email: String?,
      @RequestParam userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    try {
      defaultVoterStore.insert(userId)
      redirectAttributes.successMessage = "Default voter added."
    } catch (e: Exception) {
      log.error("Failed to add default voter $userId", e)
      redirectAttributes.failureMessage = "Failed to default add voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  @PostMapping("/voters/default/remove")
  fun removeDefaultVoter(
      @RequestParam email: String?,
      @RequestParam userIds: List<UserId>,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    try {
      userIds.forEach { defaultVoterStore.delete(it) }
      redirectAttributes.successMessage = "Default voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove default voters", e)
      redirectAttributes.failureMessage = "Failed to remove default voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  private fun redirectToVoters(email: String? = null) =
      if (email != null) "redirect:/admin/voters?email=${email}" else "redirect:/admin/voters"
}
