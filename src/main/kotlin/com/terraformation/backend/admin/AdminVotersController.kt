package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.db.DefaultVoterStore
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.log.perClassLogger
import org.jooq.DSLContext
import org.jooq.Record
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
class AdminVotersController(
    private val defaultVoterStore: DefaultVoterStore,
    private val dslContext: DSLContext,
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

    val projects = getAcceleratorProjects()
    val projectVoters =
        projects.associateBy(
            { it.projectId },
            { project ->
              voteStore
                  .fetchAllVotes(project.projectId)
                  .filter { it.phase === project.phase }
                  .map { it.userId }
                  .toSet()
            })

    val defaultVoters = defaultVoterStore.findAll()
    val users =
        defaultVoters
            .union(projectVoters.values.reduce { acc, userIds -> acc.union(userIds) })
            .map { userStore.fetchOneById(it) }
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

    val projects = getAcceleratorProjects().associateBy { it.projectId }
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
      @RequestParam userId: UserId,
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateProjectVotes(projectId) }

    val projects = getAcceleratorProjects().associateBy { it.projectId }
    try {
      voteStore.delete(projectId, projects[projectId]!!.phase, userId)
      redirectAttributes.successMessage = "Voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove voter $userId from project $projectId", e)
      redirectAttributes.failureMessage = "Failed to remove voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  @PostMapping("/voters/bulkRemove")
  fun bulkRemoveVoter(
      @RequestParam email: String?,
      @RequestParam userIds: List<UserId>?,
      @RequestParam projectId: ProjectId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateProjectVotes(projectId) }

    val projects = getAcceleratorProjects().associateBy { it.projectId }
    try {
      userIds?.forEach { voteStore.delete(projectId, projects[projectId]!!.phase, it) }
      redirectAttributes.successMessage = "Voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove voters from project $projectId", e)
      redirectAttributes.failureMessage = "Failed to bulk remove voter: ${e.message}"
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
      @RequestParam userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    try {
      defaultVoterStore.delete(userId)
      redirectAttributes.successMessage = "Default voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove default voter $userId", e)
      redirectAttributes.failureMessage = "Failed to remove default voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  @PostMapping("/voters/default/bulkRemove")
  fun bulkRemoveVoter(
      @RequestParam email: String?,
      @RequestParam userIds: List<UserId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    try {
      userIds?.forEach { defaultVoterStore.delete(it) }
      redirectAttributes.successMessage = "Voter removed."
    } catch (e: Exception) {
      log.error("Failed to remove voters as default voters", e)
      redirectAttributes.failureMessage = "Failed to bulk remove default voter: ${e.message}"
    }

    return redirectToVoters(email)
  }

  private fun getAcceleratorProjects(): List<AcceleratorProject> {
    return dslContext
        .select(
            COHORTS.NAME,
            COHORTS.PHASE_ID,
            PROJECTS.ID,
            PROJECTS.NAME,
            PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID)
        .from(PROJECTS)
        .join(PARTICIPANTS)
        .on(PARTICIPANTS.ID.eq(PROJECTS.PARTICIPANT_ID))
        .join(COHORTS)
        .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
        .leftJoin(PROJECT_VOTE_DECISIONS)
        .on(PROJECT_VOTE_DECISIONS.PROJECT_ID.eq(PROJECTS.ID))
        .orderBy(COHORTS.ID, PROJECTS.ID)
        .fetch { AcceleratorProject.of(it) }
  }

  data class AcceleratorProject(
      val phase: CohortPhase,
      val phaseName: String,
      val projectId: ProjectId,
      val projectName: String,
      val voteDecision: VoteOption?
  ) {
    companion object {
      fun of(
          record: Record,
      ): AcceleratorProject {
        return AcceleratorProject(
            phase = record[COHORTS.PHASE_ID]!!,
            phaseName = getPhaseName(record[COHORTS.PHASE_ID]!!),
            projectId = record[PROJECTS.ID]!!,
            projectName = record[PROJECTS.NAME]!!,
            voteDecision = record[PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID],
        )
      }

      private fun getPhaseName(phase: CohortPhase): String =
          when (phase) {
            CohortPhase.Phase0DueDiligence -> "Phase 0"
            CohortPhase.Phase1FeasibilityStudy -> "Phase 1"
            CohortPhase.Phase2PlanAndScale -> "Phase 2"
            CohortPhase.Phase3ImplementAndMonitor -> "Phase 3"
          }
    }
  }

  private fun redirectToVoters(email: String? = null) =
      if (email != null) "redirect:/admin/voters?email=${email}" else "redirect:/admin/voters"
}
