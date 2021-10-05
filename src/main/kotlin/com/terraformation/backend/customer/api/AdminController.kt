package com.terraformation.backend.customer.api

import com.terraformation.backend.api.RequireExistingAdminRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.LayerType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SiteNotFoundException
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserType
import com.terraformation.backend.gis.db.LayerStore
import com.terraformation.backend.gis.model.LayerModel
import com.terraformation.backend.log.perClassLogger
import java.math.BigDecimal
import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.servlet.http.HttpServletRequest
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotBlank
import net.postgis.jdbc.geometry.Point
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireExistingAdminRole
@Validated
class AdminController(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val layerStore: LayerStore,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val siteStore: SiteStore,
    private val userStore: UserStore,
) {
  private val log = perClassLogger()
  private val prefix = "/admin"

  /** Redirects /admin to /admin/ so relative URLs in the UI will work. */
  @GetMapping
  fun redirectToTrailingSlash(): String {
    return "redirect:/admin/"
  }

  @GetMapping("/")
  fun getIndex(model: Model): String {
    val organizations = organizationStore.fetchAll().sortedBy { it.id.value }

    model.addAttribute("organizations", organizations)
    model.addAttribute("prefix", prefix)

    return "/admin/index"
  }

  @GetMapping("/organization/{organizationId}")
  fun getOrganization(@PathVariable organizationId: OrganizationId, model: Model): String {
    val organization =
        organizationStore.fetchById(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
    val projects = projectStore.fetchByOrganization(organizationId).sortedBy { it.name }
    val users = organizationStore.fetchUsers(listOf(organizationId)).sortedBy { it.email }

    if (currentUser().canListApiKeys(organizationId)) {
      val apiClients =
          users.filter { it.userType == UserType.APIClient }.map {
            it.copy(email = it.email.substringAfter(config.keycloak.apiClientUsernamePrefix))
          }

      // Thymeleaf templates only know how to render Instant in the server's time zone, so we
      // need to format the timestamps here. In a real admin UI we'd let the client render these
      // in the browser's time zone.
      val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm 'UTC'")
      val createdTimes =
          apiClients.associate {
            it.email to ZonedDateTime.ofInstant(it.createdTime, ZoneOffset.UTC).format(formatter)
          }

      model.addAttribute("apiClients", apiClients)
      model.addAttribute("apiClientCreatedTimes", createdTimes)
    }

    model.addAttribute("canAddUser", currentUser().canAddOrganizationUser(organizationId))
    model.addAttribute("canCreateApiKey", currentUser().canCreateApiKey(organizationId))
    model.addAttribute("canCreateProject", currentUser().canCreateProject(organizationId))
    model.addAttribute("canDeleteApiKey", currentUser().canDeleteApiKey(organizationId))
    model.addAttribute("canListApiKeys", currentUser().canListApiKeys(organizationId))
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("projects", projects)
    model.addAttribute("roles", Role.values())
    model.addAttribute("users", users.filter { it.userType != UserType.APIClient })

    return "/admin/organization"
  }

  @GetMapping("/project/{projectId}")
  fun getProject(@PathVariable projectId: ProjectId, model: Model): String {
    val project = projectStore.fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
    val organization = organizationStore.fetchById(project.organizationId)
    val orgUsers =
        organizationStore.fetchUsers(listOf(project.organizationId)).sortedBy { it.email }
    val projectUsers = orgUsers.filter { projectId in it.projectIds }
    val availableUsers = orgUsers.filter { projectId !in it.projectIds }
    val sites = siteStore.fetchByProjectId(projectId).sortedBy { it.name }

    val defaultLayerTypes = listOf(LayerType.PlantsPlanted)
    val otherLayerTypes =
        LayerType.values().filter { it !in defaultLayerTypes }.sortedBy { it.displayName }

    model.addAttribute("availableUsers", availableUsers)
    model.addAttribute("canCreateSite", currentUser().canCreateSite(projectId))
    model.addAttribute("defaultLayerTypes", defaultLayerTypes)
    model.addAttribute("otherLayerTypes", otherLayerTypes)
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("project", project)
    model.addAttribute("sites", sites)
    model.addAttribute("users", projectUsers)

    return "/admin/project"
  }

  @GetMapping("/site/{siteId}")
  fun getSite(@PathVariable siteId: SiteId, model: Model): String {
    val site = siteStore.fetchById(siteId) ?: throw SiteNotFoundException(siteId)
    val projectId = site.projectId
    val project = projectStore.fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
    val organization = organizationStore.fetchById(project.organizationId)
    val facilities = facilityStore.fetchBySiteId(siteId).sortedBy { it.name }
    val layers = layerStore.listLayers(siteId).sortedBy { it.layerType.displayName }

    model.addAttribute("canCreateFacility", currentUser().canCreateFacility(siteId))
    model.addAttribute("canCreateLayer", currentUser().canCreateLayer(siteId))
    model.addAttribute("facilities", facilities)
    model.addAttribute("facilityTypes", FacilityType.values())
    model.addAttribute("layers", layers)
    model.addAttribute("layerTypes", LayerType.values().sortedBy { it.displayName })
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("project", project)
    model.addAttribute("site", site)

    return "/admin/site"
  }

  @GetMapping("/user/{userId}")
  fun getUser(@PathVariable userId: UserId, model: Model): String {
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
      @RequestParam("userId") userId: UserId,
      @RequestParam("organizationId", required = false) organizationIdList: List<OrganizationId>?,
      @RequestParam("role", required = false) roleValues: List<String>?,
      @RequestParam("projectId", required = false) projectIdList: List<ProjectId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    val organizationIds = organizationIdList?.toSet() ?: emptySet()
    val projectIds = projectIdList?.toSet() ?: emptySet()

    // Roles are of the form orgId:roleId
    val roles =
        roleValues?.map { it.split(':') }?.associate {
          OrganizationId(it[0].toLong()) to Role.of(it[1].toInt())
        }
            ?: emptyMap()

    val user = userStore.fetchById(userId)
    if (user == null) {
      redirectAttributes.addFlashAttribute("failureMessage", "User not found.")
      return adminHome()
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

    redirectAttributes.addFlashAttribute("successMessage", "User memberships updated.")

    return user(userId)
  }

  @PostMapping("/createOrganization")
  fun createOrganization(
      @NotBlank @RequestParam("name") name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val org = organizationStore.createWithAdmin(name)
      redirectAttributes.addFlashAttribute("successMessage", "Created organization ${org.id}")
    } catch (e: Exception) {
      log.error("Failed to create organization $name", e)
      redirectAttributes.addFlashAttribute("failureMessage", "Failed to create organization")
    }

    return adminHome()
  }

  @PostMapping("/createUser")
  fun createUser(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @NotBlank @RequestParam("email") email: String,
      @RequestParam("firstName") firstName: String?,
      @RequestParam("lastName") lastName: String?,
      @RequestParam("role") roleId: Int,
      request: HttpServletRequest,
      redirectAttributes: RedirectAttributes,
  ): String {
    val role = Role.of(roleId)
    if (role == null) {
      redirectAttributes.addFlashAttribute("failureMessage", "Invalid role selected.")
      return organization(organizationId)
    }

    try {
      val redirectUrl =
          config.keycloak.postCreateRedirectUrl ?: URI(request.requestURL.toString()).resolve("/")
      val user =
          userStore.createUser(
              organizationId, role, email, firstName, lastName, redirectUrl = redirectUrl)

      redirectAttributes.addFlashAttribute("successMessage", "User added to organization.")

      return user(user.userId)
    } catch (e: DuplicateKeyException) {
      redirectAttributes.addFlashAttribute("failureMessage", "User is already in the organization.")
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to create users in this organization.")
    } catch (e: Exception) {
      log.error("User creation failed", e)
      redirectAttributes.addFlashAttribute(
          "failureMessage", "Unexpected failure while creating user.")
    }

    return organization(organizationId)
  }

  @PostMapping("/removeOrganizationUser")
  fun removeOrganizationUser(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @RequestParam("userId") userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      if (organizationStore.removeUser(organizationId, userId)) {
        redirectAttributes.addFlashAttribute("successMessage", "User removed from organization.")
      } else {
        redirectAttributes.addFlashAttribute(
            "failureMessage", "User was not a member of the organization.")
      }
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to remove users from this organization.")
    }

    return organization(organizationId)
  }

  @PostMapping("/setOrganizationUserRole")
  fun setOrganizationUserRole(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @RequestParam("userId") userId: UserId,
      @RequestParam("role") roleId: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    val role = Role.of(roleId)

    if (role == null) {
      redirectAttributes.addFlashAttribute("failureMessage", "Invalid role selected.")
      return organization(organizationId)
    }

    try {
      if (organizationStore.setUserRole(organizationId, userId, role)) {
        redirectAttributes.addFlashAttribute("successMessage", "User role updated.")
      } else {
        redirectAttributes.addFlashAttribute(
            "failureMessage", "User was not a member of the organization.")
      }
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to set user roles for this organization.")
    }

    return organization(organizationId)
  }

  @PostMapping("/createProject")
  fun createProject(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @NotBlank @RequestParam("name") name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectStore.create(organizationId, name)
      redirectAttributes.addFlashAttribute("successMessage", "Project created.")
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to create projects in this organization.")
    }

    return organization(organizationId)
  }

  @PostMapping("/removeProjectUser")
  fun removeProjectUser(
      @RequestParam("projectId") projectId: ProjectId,
      @RequestParam("userId") userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      if (projectStore.removeUser(projectId, userId)) {
        redirectAttributes.addFlashAttribute("successMessage", "User removed from project.")
      } else {
        redirectAttributes.addFlashAttribute(
            "failureMessage", "User was not a member of the project.")
      }
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to remove users from this project.")
    }

    return project(projectId)
  }

  @PostMapping("/addProjectUser")
  fun addProjectUser(
      @RequestParam("projectId") projectId: ProjectId,
      @RequestParam("userId") userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectStore.addUser(projectId, userId)
      redirectAttributes.addFlashAttribute("successMessage", "User added to project.")
    } catch (e: AccessDeniedException) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "No permission to add users to this project.")
    } catch (e: DuplicateKeyException) {
      redirectAttributes.addFlashAttribute("failureMessage", "User is already in this project.")
    }

    return project(projectId)
  }

  @PostMapping("/createSite")
  fun createSite(
      @RequestParam("projectId") projectId: ProjectId,
      @NotBlank @RequestParam("name") name: String,
      @Min(-180L) @Max(180L) @RequestParam("latitude") latitude: BigDecimal,
      @Min(-180L) @Max(180L) @RequestParam("longitude") longitude: BigDecimal,
      @RequestParam("layerTypes", required = false) layerTypes: List<LayerType>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    val location =
        Point(longitude.toDouble(), latitude.toDouble(), 0.0).apply { srid = SRID.LONG_LAT }
    val site = siteStore.create(projectId, name, location)

    try {
      layerTypes?.forEach { layerType ->
        layerStore.createLayer(
            LayerModel(
                hidden = false,
                layerType = layerType,
                proposed = false,
                siteId = site.id,
                tileSetName = null,
            ))
      }

      redirectAttributes.addFlashAttribute("successMessage", "Site created.")
    } catch (e: Exception) {
      log.error("Layer creation failed", e)
      redirectAttributes.addFlashAttribute(
          "failureMessage", "Created site but could not create layers.")
    }

    return project(projectId)
  }

  @PostMapping("/createLayer")
  fun createLayer(
      @RequestParam("siteId") siteId: SiteId,
      @RequestParam("layerType") layerType: LayerType,
      redirectAttributes: RedirectAttributes,
  ): String {
    val layer =
        layerStore.createLayer(
            LayerModel(
                hidden = false,
                layerType = layerType,
                proposed = false,
                siteId = siteId,
                tileSetName = null,
            ))

    redirectAttributes.addFlashAttribute("successMessage", "Layer ${layer.id} created.")

    return site(siteId)
  }

  @PostMapping("/createFacility")
  fun createFacility(
      @RequestParam("siteId") siteId: SiteId,
      @NotBlank @RequestParam("name") name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.addFlashAttribute("failureMessage", "Unknown facility type.")
      return site(siteId)
    }

    facilityStore.create(siteId, name, type)

    redirectAttributes.addFlashAttribute("successMessage", "Facility created.")

    return site(siteId)
  }

  @PostMapping("/createApiKey")
  fun createApiKey(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @RequestParam("description") description: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    val newUser = userStore.createApiClient(organizationId, description)

    val token = userStore.generateOfflineToken(newUser.userId)

    redirectAttributes.addFlashAttribute("authId", newUser.authId)
    redirectAttributes.addFlashAttribute(
        "keyId", newUser.email.substringAfter(config.keycloak.apiClientUsernamePrefix))
    redirectAttributes.addFlashAttribute("prefix", prefix)
    redirectAttributes.addFlashAttribute("token", token)

    return apiKeyAdded(organizationId)
  }

  @GetMapping("/apiKeyAdded/{organizationId}")
  fun getApiKeyAdded(
      @PathVariable("organizationId") organizationId: OrganizationId,
      model: Model,
      redirectAttributes: RedirectAttributes
  ): String {
    if (!model.containsAttribute("token")) {
      redirectAttributes.addFlashAttribute(
          "failureMessage", "You may not view an API key after it has been created.")
      return organization(organizationId)
    }

    val organization = organizationStore.fetchById(organizationId)

    model.addAttribute("organization", organization)

    return "/admin/apiKeyAdded"
  }

  @PostMapping("/deleteApiKey")
  fun deleteApiKey(
      @RequestParam("organizationId") organizationId: OrganizationId,
      @RequestParam("userId") userId: UserId,
      redirectAttributes: RedirectAttributes,
  ): String {
    if (userStore.deleteApiClient(userId)) {
      redirectAttributes.addFlashAttribute("successMessage", "API key deleted.")
    } else {
      redirectAttributes.addFlashAttribute("failureMessage", "Unable to delete API key.")
    }

    return organization(organizationId)
  }

  /** Returns a redirect view name for an admin endpoint. */
  private fun redirect(endpoint: String) = "redirect:${prefix}$endpoint"

  // Convenience methods to redirect to the GET endpoint for each kind of thing.

  private fun adminHome() = redirect("/")
  private fun apiKeyAdded(organizationId: OrganizationId) = redirect("/apiKeyAdded/$organizationId")
  private fun organization(organizationId: OrganizationId) =
      redirect("/organization/$organizationId")
  private fun project(projectId: ProjectId) = redirect("/project/$projectId")
  private fun site(siteId: SiteId) = redirect("/site/$siteId")
  private fun user(userId: UserId) = redirect("/user/$userId")
}
