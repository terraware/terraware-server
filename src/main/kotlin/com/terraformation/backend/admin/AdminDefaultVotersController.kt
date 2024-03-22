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
class AdminDefaultVotersController(
    private val userStore: UserStore,
    private val defaultVoterStore: DefaultVoterStore,
    private val dslContext: DSLContext,
    private val voteStore: VoteStore
) {
  private val log = perClassLogger()

  @GetMapping("/defaultVoters")
  fun getDefaultVoters(
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    val projects = getAcceleratorProjects()

    model.addAttribute("users", defaultVoterStore.findAll().map { userStore.fetchOneById(it) })
    model.addAttribute("projects", projects)

    return "/admin/defaultVoters"
  }

  @PostMapping("/defaultVoters/add")
  fun addDefaultVoter(
      @RequestParam email: String,
      @RequestParam projectIds: List<ProjectId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    val userIdForEmail = userStore.fetchByEmail(email)?.userId
    if (userIdForEmail == null) {
      redirectAttributes.failureMessage = "$email not found."
      return redirectToDefaultVoter()
    }

    try {
      defaultVoterStore.insert(userIdForEmail)
      redirectAttributes.successMessage = "Default voter added."

      projectIds?.forEach { voteStore.upsert(it, getProjectPhase(it)!!, userIdForEmail) }
    } catch (e: Exception) {
      log.error("Failed to add default voter", e)
      redirectAttributes.failureMessage = "Failed to add default voter: ${e.message}"
    }

    return redirectToDefaultVoter()
  }

  @PostMapping("/defaultVoters/remove")
  fun removeDefaultVoter(
      @RequestParam userId: UserId,
      @RequestParam projectIds: List<ProjectId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { updateDefaultVoters() }

    val projects = getAcceleratorProjects()

    try {
      defaultVoterStore.delete(userId)
      redirectAttributes.successMessage = "Default voter removed."

      projectIds?.forEach { voteStore.upsert(it, getProjectPhase(it)!!, userId) }
    } catch (e: Exception) {
      log.error("Failed to add default voter", e)
      redirectAttributes.failureMessage = "Failed to remove default voter: ${e.message}"
    }

    return redirectToDefaultVoter()
  }

  private fun redirectToDefaultVoter() = "redirect:/admin/defaultVoters"

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

  private fun getProjectPhase(projectId: ProjectId): CohortPhase? {
    return getAcceleratorProjects().firstOrNull { it.projectId == projectId }?.phase
  }
}

data class AcceleratorProject(
    val cohortName: String,
    val phase: CohortPhase,
    val projectId: ProjectId,
    val projectName: String,
    val voteDecision: VoteOption?
) {
  companion object {
    fun of(
        record: Record,
    ): AcceleratorProject {
      return AcceleratorProject(
          cohortName = record[COHORTS.NAME]!!,
          phase = record[COHORTS.PHASE_ID]!!,
          projectId = record[PROJECTS.ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          voteDecision = record[PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID],
      )
    }
  }
}
