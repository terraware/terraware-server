package com.terraformation.backend.customer.api

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.log.perClassLogger
import java.math.BigDecimal
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotBlank
import javax.ws.rs.NotFoundException
import org.jooq.DSLContext
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@RequestMapping("/admin")
@Controller
@Validated
class AdminController(
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val siteStore: SiteStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()
  private val prefix = "/admin"

  @GetMapping
  fun index(model: Model): String {
    val organizations = organizationStore.fetchAll().sortedBy { it.id.value }

    model.addAttribute("organizations", organizations)
    model.addAttribute("prefix", prefix)

    return "/admin/index"
  }

  @GetMapping("/organization/{organizationId}")
  fun organization(@PathVariable("organizationId") idValue: Long, model: Model): String {
    val organizationId = OrganizationId(idValue)
    val organization =
        organizationStore.fetchById(organizationId)
            ?: throw com.terraformation.backend.api.NotFoundException()
    val projects = projectStore.fetchByOrganization(organizationId).sortedBy { it.name }
    val users = organizationStore.fetchUsers(listOf(organizationId)).sortedBy { it.email }

    model.addAttribute("canAddUser", currentUser().canAddOrganizationUser(organizationId))
    model.addAttribute("canCreateProject", currentUser().canCreateProject(organizationId))
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("projects", projects)
    model.addAttribute("roles", Role.values())
    model.addAttribute("users", users)

    return "/admin/organization"
  }

  @GetMapping("/project/{projectId}")
  fun project(@PathVariable("projectId") idValue: Long, model: Model): String {
    val projectId = ProjectId(idValue)
    val project =
        projectStore.fetchById(projectId)
            ?: throw com.terraformation.backend.api.NotFoundException()
    val organization = organizationStore.fetchById(project.organizationId)
    val orgUsers =
        organizationStore.fetchUsers(listOf(project.organizationId)).sortedBy { it.email }
    val projectUsers = orgUsers.filter { projectId in it.projectIds }
    val availableUsers = orgUsers.filter { projectId !in it.projectIds }
    val sites = siteStore.fetchByProjectId(projectId).sortedBy { it.name }

    model.addAttribute("availableUsers", availableUsers)
    model.addAttribute("canCreateSite", currentUser().canCreateSite(projectId))
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("project", project)
    model.addAttribute("sites", sites)
    model.addAttribute("users", projectUsers)

    return "/admin/project"
  }

  @GetMapping("/site/{siteId}")
  fun site(@PathVariable("siteId") idValue: Long, model: Model): String {
    val siteId = SiteId(idValue)
    val site = siteStore.fetchById(siteId) ?: throw NotFoundException()
    val projectId = site.projectId
    val project = projectStore.fetchById(projectId) ?: throw NotFoundException()
    val organization = organizationStore.fetchById(project.organizationId)
    val facilities = facilityStore.fetchBySiteId(siteId).sortedBy { it.name }

    model.addAttribute("canCreateFacility", currentUser().canCreateFacility(siteId))
    model.addAttribute("facilities", facilities)
    model.addAttribute("facilityTypes", FacilityType.values())
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("project", project)
    model.addAttribute("site", site)

    return "/admin/site"
  }

  @GetMapping("/user/{userId}")
  fun user(@PathVariable("userId") idValue: Long, model: Model): String {
    val userId = UserId(idValue)
    val user = userStore.fetchById(userId)
    val organizations = organizationStore.fetchAll() // TODO: Only ones where currentUser is admin?
    val projects = projectStore.fetchAll().groupBy { it.organizationId }

    model.addAttribute("organizations", organizations)
    model.addAttribute("prefix", prefix)
    model.addAttribute("projects", projects)
    model.addAttribute("roles", Role.values())
    model.addAttribute("user", user)

    return "/admin/user"
  }

  @PostMapping("/setUserMemberships")
  fun setUserMemberships(
      @RequestParam("userId") userIdValue: Long,
      @RequestParam("organizationId", required = false) organizationIdValues: List<Long>?,
      @RequestParam("role", required = false) roleValues: List<String>?,
      @RequestParam("projectId", required = false) projectIdValues: List<Long>?,
      model: Model
  ): String {
    val organizationIds = organizationIdValues?.map { OrganizationId(it) }?.toSet() ?: emptySet()
    val projectIds = projectIdValues?.map { ProjectId(it) }?.toSet() ?: emptySet()
    val userId = UserId(userIdValue)

    // Roles are of the form orgId:roleId
    val roles =
        roleValues?.map { it.split(':') }?.associate {
          OrganizationId(it[0].toLong()) to Role.of(it[1].toInt())
        }
            ?: emptyMap()

    val user = userStore.fetchById(userId)
    if (user == null) {
      model.addAttribute("failureMessage", "User not found.")
      return index(model)
    }

    // We need to know which boxes were unchecked; the UI would have shown all the orgs and projects
    // the current user can administer.
    val adminOrganizationIds =
        organizationStore
            .fetchAll()
            .map { it.id }
            .filter {
              currentUser().canRemoveOrganizationUser(it) ||
                  currentUser().canAddOrganizationUser(it)
            }
            .toSet()
    val adminProjectIds =
        projectStore
            .fetchAll()
            .map { it.id }
            .filter {
              currentUser().canRemoveProjectUser(it) || currentUser().canAddProjectUser(it)
            }
            .toSet()

    val organizationsToAdd = organizationIds - user.organizationRoles.keys
    val organizationsToRemove = adminOrganizationIds - organizationIds
    val organizationsToUpdate = organizationIds.filter { user.organizationRoles[it] != roles[it] }
    val projectsToAdd = projectIds - user.projectRoles.keys
    val projectsToRemove = adminProjectIds - projectIds

    dslContext.transaction { _ ->
      organizationsToAdd.forEach { organizationId ->
        organizationStore.addUser(organizationId, userId, roles[organizationId] ?: Role.CONTRIBUTOR)
      }
      organizationsToRemove.forEach { organizationId ->
        organizationStore.removeUser(organizationId, userId)
      }
      organizationsToUpdate.forEach { organizationId ->
        roles[organizationId]?.let { newRole ->
          organizationStore.setUserRole(organizationId, userId, newRole)
        }
      }

      projectsToAdd.forEach { projectId -> projectStore.addUser(projectId, userId) }
      projectsToRemove.forEach { projectId -> projectStore.removeUser(projectId, userId) }
    }

    model.addAttribute("successMessage", "User memberships updated.")

    return user(userIdValue, model)
  }

  @PostMapping("/createOrganization")
  fun createOrganization(@NotBlank @RequestParam name: String, model: Model): String {
    try {
      val org = organizationStore.createWithAdmin(name)
      model.addAttribute("successMessage", "Created organization ${org.id}")
    } catch (e: Exception) {
      log.error("Failed to create organization $name", e)
      model.addAttribute("failureMessage", "Failed to create organization")
    }

    return index(model)
  }

  @PostMapping("/addOrganizationUser")
  fun addOrganizationUser(
      @RequestParam("organizationId") organizationIdValue: Long,
      @NotBlank @RequestParam("email") email: String,
      @RequestParam("role") roleId: Int,
      model: Model
  ): String {
    val organizationId = OrganizationId(organizationIdValue)
    val role = Role.of(roleId)

    if (role == null) {
      model.addAttribute("failureMessage", "Invalid role selected.")
      return organization(organizationIdValue, model)
    }

    val userDetails = userStore.fetchByEmail(email)
    if (userDetails == null) {
      model.addAttribute("failureMessage", "User $email not found.")
      return organization(organizationIdValue, model)
    }

    try {
      organizationStore.addUser(organizationId, userDetails.userId, role)
      model.addAttribute("successMessage", "$email added to organization.")
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to add users to this organization.")
    } catch (e: DuplicateKeyException) {
      model.addAttribute("failureMessage", "$email was already a member of the organization.")
    }

    return organization(organizationIdValue, model)
  }

  @PostMapping("/removeOrganizationUser")
  fun removeOrganizationUser(
      @RequestParam("organizationId") organizationIdValue: Long,
      @RequestParam("userId") userIdValue: Long,
      model: Model
  ): String {
    val organizationId = OrganizationId(organizationIdValue)
    val userId = UserId(userIdValue)

    try {
      if (organizationStore.removeUser(organizationId, userId)) {
        model.addAttribute("successMessage", "User removed from organization.")
      } else {
        model.addAttribute("failureMessage", "User was not a member of the organization.")
      }
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to remove users from this organization.")
    }

    return organization(organizationIdValue, model)
  }

  @PostMapping("/setOrganizationUserRole")
  fun setOrganizationUserRole(
      @RequestParam("organizationId") organizationIdValue: Long,
      @RequestParam("userId") userIdValue: Long,
      @RequestParam("role") roleId: Int,
      model: Model
  ): String {
    val organizationId = OrganizationId(organizationIdValue)
    val role = Role.of(roleId)
    val userId = UserId(userIdValue)

    if (role == null) {
      model.addAttribute("failureMessage", "Invalid role selected.")
      return organization(organizationIdValue, model)
    }

    try {
      if (organizationStore.setUserRole(organizationId, userId, role)) {
        model.addAttribute("successMessage", "User role updated.")
      } else {
        model.addAttribute("failureMessage", "User was not a member of the organization.")
      }
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to set user roles for this organization.")
    }

    return organization(organizationIdValue, model)
  }

  @PostMapping("/createProject")
  fun createProject(
      @RequestParam("organizationId") organizationIdValue: Long,
      @NotBlank @RequestParam("name") name: String,
      model: Model
  ): String {
    val organizationId = OrganizationId(organizationIdValue)

    try {
      projectStore.create(organizationId, name)
      model.addAttribute("successMessage", "Project created.")
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to create projects in this organization.")
    }

    return organization(organizationIdValue, model)
  }

  @PostMapping("/removeProjectUser")
  fun removeProjectUser(
      @RequestParam("projectId") projectIdValue: Long,
      @RequestParam("userId") userIdValue: Long,
      model: Model
  ): String {
    val projectId = ProjectId(projectIdValue)
    val userId = UserId(userIdValue)

    try {
      if (projectStore.removeUser(projectId, userId)) {
        model.addAttribute("successMessage", "User removed from project.")
      } else {
        model.addAttribute("failureMessage", "User was not a member of the project.")
      }
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to remove users from this project.")
    }

    return project(projectIdValue, model)
  }

  @PostMapping("/addProjectUser")
  fun addProjectUser(
      @RequestParam("projectId") projectIdValue: Long,
      @RequestParam("userId") userIdValue: Long,
      model: Model
  ): String {
    val projectId = ProjectId(projectIdValue)
    val userId = UserId(userIdValue)

    try {
      projectStore.addUser(projectId, userId)
      model.addAttribute("successMessage", "User added to project.")
    } catch (e: AccessDeniedException) {
      model.addAttribute("failureMessage", "No permission to add users to this project.")
    } catch (e: DuplicateKeyException) {
      model.addAttribute("failureMessage", "User is already in this project.")
    }

    return project(projectIdValue, model)
  }

  @PostMapping("/createSite")
  fun createSite(
      @RequestParam("projectId") projectIdValue: Long,
      @NotBlank @RequestParam("name") name: String,
      @Min(-180L) @Max(180L) @RequestParam("latitude") latitude: BigDecimal,
      @Min(-180L) @Max(180L) @RequestParam("longitude") longitude: BigDecimal,
      model: Model
  ): String {
    val projectId = ProjectId(projectIdValue)

    siteStore.create(projectId, name, latitude, longitude)

    model.addAttribute("successMessage", "Site created.")
    return project(projectIdValue, model)
  }

  @PostMapping("/createFacility")
  fun createFacility(
      @RequestParam("siteId") siteIdValue: Long,
      @NotBlank @RequestParam("name") name: String,
      @RequestParam("type") typeId: Int,
      model: Model
  ): String {
    val siteId = SiteId(siteIdValue)
    val type = FacilityType.forId(typeId)

    if (type == null) {
      model.addAttribute("failureMessage", "Unknown facility type.")
      return site(siteIdValue, model)
    }

    facilityStore.create(siteId, name, type)

    model.addAttribute("successMessage", "Facility created.")
    return site(siteIdValue, model)
  }
}
