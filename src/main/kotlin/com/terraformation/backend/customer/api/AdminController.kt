package com.terraformation.backend.customer.api

import com.terraformation.backend.api.RequireExistingAdminRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.model.Role
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.DeviceManagerId
import com.terraformation.backend.db.DeviceManagerNotFoundException
import com.terraformation.backend.db.DeviceTemplateCategory
import com.terraformation.backend.db.FacilityConnectionState
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SiteNotFoundException
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.StorageLocationId
import com.terraformation.backend.db.UserId
import com.terraformation.backend.db.UserNotFoundException
import com.terraformation.backend.db.UserType
import com.terraformation.backend.db.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.tables.pojos.DeviceTemplatesRow
import com.terraformation.backend.db.tables.pojos.DevicesRow
import com.terraformation.backend.db.tables.pojos.OrganizationsRow
import com.terraformation.backend.db.tables.pojos.SitesRow
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.db.GbifImporter
import java.math.BigDecimal
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipFile
import javax.servlet.http.HttpServletRequest
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotBlank
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.random.Random
import net.postgis.jdbc.geometry.Point
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.tomcat.util.buf.HexUtils
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.propertyeditors.StringTrimmerEditor
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.InitBinder
import org.springframework.web.bind.annotation.ModelAttribute
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
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceStore: DeviceStore,
    private val deviceTemplatesDao: DeviceTemplatesDao,
    private val dslContext: DSLContext,
    private val facilityStore: FacilityStore,
    private val gbifImporter: GbifImporter,
    private val organizationStore: OrganizationStore,
    private val projectStore: ProjectStore,
    private val publisher: ApplicationEventPublisher,
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

    model.addAttribute("canImportGlobalSpeciesData", currentUser().canImportGlobalSpeciesData())
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
    val users = organizationStore.fetchUsers(organizationId).sortedBy { it.email }

    if (currentUser().canListApiKeys(organizationId)) {
      val apiClients =
          users
              .filter { it.userType == UserType.APIClient }
              .map {
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
    val orgUsers = organizationStore.fetchUsers(project.organizationId).sortedBy { it.email }
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
  fun getSite(@PathVariable siteId: SiteId, model: Model): String {
    val site = siteStore.fetchById(siteId) ?: throw SiteNotFoundException(siteId)
    val projectId = site.projectId
    val project = projectStore.fetchById(projectId) ?: throw ProjectNotFoundException(projectId)
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

  @GetMapping("/facility/{facilityId}")
  fun getFacility(@PathVariable facilityId: FacilityId, model: Model): String {
    val facility =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)
    val site = siteStore.fetchById(facility.siteId) ?: throw SiteNotFoundException(facility.siteId)
    val project =
        projectStore.fetchById(site.projectId) ?: throw ProjectNotFoundException(site.projectId)
    val organization =
        organizationStore.fetchById(project.organizationId)
            ?: throw OrganizationNotFoundException(project.organizationId)
    val recipients = projectStore.fetchEmailRecipients(project.id)
    val storageLocations = facilityStore.fetchStorageLocations(facilityId)
    val deviceManager = deviceManagerStore.fetchOneByFacilityId(facilityId)
    val devices = deviceStore.fetchByFacilityId(facilityId)

    model.addAttribute("canUpdateFacility", currentUser().canUpdateFacility(facilityId))
    model.addAttribute("connectionStates", FacilityConnectionState.values())
    model.addAttribute("devices", devices)
    model.addAttribute("deviceManager", deviceManager)
    model.addAttribute("facility", facility)
    model.addAttribute("facilityTypes", FacilityType.values())
    model.addAttribute("organization", organization)
    model.addAttribute("prefix", prefix)
    model.addAttribute("project", project)
    model.addAttribute("recipients", recipients)
    model.addAttribute("site", site)
    model.addAttribute("storageConditions", StorageCondition.values())
    model.addAttribute("storageLocations", storageLocations)

    return "/admin/facility"
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

  @GetMapping("/deviceTemplates")
  fun getDeviceTemplates(model: Model): String {
    val templates = deviceTemplatesDao.findAll().sortedBy { it.id!!.value }

    model.addAttribute("canUpdateDeviceTemplates", currentUser().canUpdateDeviceTemplates())
    model.addAttribute("categories", DeviceTemplateCategory.values())
    model.addAttribute("prefix", prefix)
    model.addAttribute("templates", templates)

    return "/admin/deviceTemplates"
  }

  @GetMapping("/deviceManagers")
  fun listDeviceManagers(model: Model): String {
    val managers = deviceManagerStore.findAll()

    model.addAttribute("canCreateDeviceManager", currentUser().canCreateDeviceManager())
    model.addAttribute("managers", managers)
    model.addAttribute("prefix", prefix)

    return "/admin/listDeviceManagers"
  }

  @GetMapping("/deviceManagers/{deviceManagerId}")
  fun getDeviceManager(
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
      model: Model
  ): String {
    val manager =
        deviceManagerStore.fetchOneById(deviceManagerId)
            ?: throw DeviceManagerNotFoundException(deviceManagerId)
    val facility = manager.facilityId?.let { facilityStore.fetchById(it) }

    model.addAttribute(
        "canUpdateDeviceManager", currentUser().canUpdateDeviceManager(deviceManagerId))
    model.addAttribute("facility", facility)
    model.addAttribute("manager", manager)
    model.addAttribute("prefix", prefix)

    return "/admin/deviceManager"
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
        roleValues
            ?.map { it.split(':') }
            ?.associate { OrganizationId(it[0].toLong()) to Role.of(it[1].toInt()) }
            ?: emptyMap()

    val user = userStore.fetchById(userId)
    if (user == null) {
      redirectAttributes.failureMessage = "User not found."
      return adminHome()
    }

    // We need to know which boxes were unchecked; the UI would have shown all the orgs and projects
    // the current user can administer.
    val adminOrganizationIds =
        organizationStore
            .fetchAll()
            .map { it.id }
            .filter { currentUser().canAddOrganizationUser(it) }
            .toSet()
    val adminProjectIds =
        projectStore.fetchAll().map { it.id }.filter { currentUser().canAddProjectUser(it) }.toSet()

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

    redirectAttributes.successMessage = "User memberships updated."

    return user(userId)
  }

  @PostMapping("/createOrganization")
  fun createOrganization(
      @NotBlank @RequestParam("name") name: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val org = organizationStore.createWithAdmin(OrganizationsRow(name = name))
      redirectAttributes.successMessage = "Created organization ${org.id}"
    } catch (e: Exception) {
      log.error("Failed to create organization $name", e)
      redirectAttributes.failureMessage = "Failed to create organization"
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
      redirectAttributes.failureMessage = "Invalid role selected."
      return organization(organizationId)
    }

    try {
      val redirectUrl =
          config.keycloak.postCreateRedirectUrl ?: URI(request.requestURL.toString()).resolve("/")
      val user =
          userStore.createUser(
              organizationId, role, email, firstName, lastName, redirectUrl = redirectUrl)

      redirectAttributes.successMessage = "User added to organization."

      return user(user.userId)
    } catch (e: DuplicateKeyException) {
      redirectAttributes.failureMessage = "User is already in the organization."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to create users in this organization."
    } catch (e: Exception) {
      log.error("User creation failed", e)
      redirectAttributes.failureMessage = "Unexpected failure while creating user."
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
      organizationStore.removeUser(organizationId, userId)
      redirectAttributes.successMessage = "User removed from organization."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to remove users from this organization."
    } catch (e: UserNotFoundException) {
      redirectAttributes.failureMessage = "User was not a member of the organization."
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
      redirectAttributes.failureMessage = "Invalid role selected."
      return organization(organizationId)
    }

    try {
      organizationStore.setUserRole(organizationId, userId, role)
      redirectAttributes.successMessage = "User role updated."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to set user roles for this organization."
    } catch (e: UserNotFoundException) {
      redirectAttributes.failureMessage = "User was not a member of the organization."
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
      redirectAttributes.successMessage = "Project created."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to create projects in this organization."
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
      projectStore.removeUser(projectId, userId)
      redirectAttributes.successMessage = "User removed from project."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to remove users from this project."
    } catch (e: UserNotFoundException) {
      redirectAttributes.failureMessage = "User was not a member of the project."
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
      redirectAttributes.successMessage = "User added to project."
    } catch (e: AccessDeniedException) {
      redirectAttributes.failureMessage = "No permission to add users to this project."
    } catch (e: DuplicateKeyException) {
      redirectAttributes.failureMessage = "User is already in this project."
    }

    return project(projectId)
  }

  @PostMapping("/createSite")
  fun createSite(
      @RequestParam("projectId") projectId: ProjectId,
      @NotBlank @RequestParam("name") name: String,
      @Min(-180L) @Max(180L) @RequestParam("latitude") latitude: BigDecimal,
      @Min(-180L) @Max(180L) @RequestParam("longitude") longitude: BigDecimal,
      redirectAttributes: RedirectAttributes,
  ): String {
    val location =
        Point(longitude.toDouble(), latitude.toDouble(), 0.0).apply { srid = SRID.LONG_LAT }

    try {
      siteStore.create(SitesRow(projectId = projectId, name = name, location = location))

      redirectAttributes.successMessage = "Site created."
    } catch (e: Exception) {
      log.error("Site creation failed", e)
      redirectAttributes.failureMessage = "Unable to create site."
    }

    return project(projectId)
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
      redirectAttributes.failureMessage = "Unknown facility type."
      return site(siteId)
    }

    facilityStore.create(siteId, type, name)

    redirectAttributes.successMessage = "Facility created."

    return site(siteId)
  }

  @PostMapping("/updateFacility")
  fun updateFacility(
      @RequestParam("connectionState") connectionState: FacilityConnectionState,
      @RequestParam("description") description: String?,
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("maxIdleMinutes") maxIdleMinutes: Int,
      @RequestParam("name") name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.failureMessage = "Unknown facility type."
      return facility(facilityId)
    }

    val existing =
        facilityStore.fetchById(facilityId) ?: throw FacilityNotFoundException(facilityId)
    facilityStore.update(
        existing.copy(
            name = name,
            description = description?.ifEmpty { null },
            type = type,
            maxIdleMinutes = maxIdleMinutes))

    if (connectionState != existing.connectionState) {
      facilityStore.updateConnectionState(facilityId, existing.connectionState, connectionState)
    }

    redirectAttributes.successMessage = "Facility updated."

    return facility(facilityId)
  }

  @PostMapping("/sendAlert")
  fun sendAlert(
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("subject") subject: String,
      @RequestParam("body") body: String,
      redirectAttributes: RedirectAttributes
  ): String {
    if (facilityStore.fetchById(facilityId)?.connectionState !=
        FacilityConnectionState.Configured) {
      redirectAttributes.successMessage =
          "Alert received, but facility is not configured so alert would be ignored."
      return facility(facilityId)
    }

    try {
      publisher.publishEvent(
          FacilityAlertRequestedEvent(facilityId, subject, body, currentUser().userId))

      redirectAttributes.successMessage =
          if (config.email.enabled) {
            "Alert sent."
          } else {
            "Alert generated and logged, but email sending is currently disabled."
          }
    } catch (e: Exception) {
      log.error("Failed to send alert", e)
      redirectAttributes.failureMessage = "Failed to send alert."
    }

    return facility(facilityId)
  }

  @PostMapping("/createStorageLocation")
  fun createStorageLocation(
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("name") name: String,
      @RequestParam("condition") condition: StorageCondition,
      redirectAttributes: RedirectAttributes
  ): String {
    facilityStore.createStorageLocation(facilityId, name, condition)

    redirectAttributes.successMessage = "Storage location created."

    return facility(facilityId)
  }

  @PostMapping("/updateStorageLocation")
  fun updateStorageLocation(
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("storageLocationId") storageLocationId: StorageLocationId,
      @RequestParam("name") name: String,
      @RequestParam("condition") condition: StorageCondition,
      redirectAttributes: RedirectAttributes
  ): String {
    facilityStore.updateStorageLocation(storageLocationId, name, condition)

    redirectAttributes.successMessage = "Storage location updated."

    return facility(facilityId)
  }

  @PostMapping("/deleteStorageLocation")
  fun deleteStorageLocation(
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("storageLocationId") storageLocationId: StorageLocationId,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      facilityStore.deleteStorageLocation(storageLocationId)
      redirectAttributes.successMessage = "Storage location deleted."
    } catch (e: DataIntegrityViolationException) {
      redirectAttributes.failureMessage = "Storage location is in use; can't delete it."
    }

    return facility(facilityId)
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
      redirectAttributes.failureMessage = "You may not view an API key after it has been created."
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
      redirectAttributes.successMessage = "API key deleted."
    } else {
      redirectAttributes.failureMessage = "Unable to delete API key."
    }

    return organization(organizationId)
  }

  @PostMapping("/uploadGbif", consumes = ["multipart/form-data"])
  fun uploadGbif(
      request: HttpServletRequest,
      redirectAttributes: RedirectAttributes,
  ): String {
    val tempFile = createTempFile(suffix = ".zip")

    try {
      log.info("Copying GBIF zipfile to local filesystem: $tempFile")
      val uploadRequestPart =
          ServletFileUpload().getItemIterator(request).next()
              ?: throw IllegalArgumentException("No uploaded file found")
      uploadRequestPart.openStream().use { uploadStream ->
        Files.copy(uploadStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
      }

      ZipFile(tempFile.toFile()).use { gbifImporter.import(it) }

      redirectAttributes.successMessage = "GBIF data imported successfully."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    } finally {
      tempFile.deleteIfExists()
    }

    return adminHome()
  }

  @PostMapping("/createDeviceTemplate")
  fun createDeviceTemplate(
      redirectAttributes: RedirectAttributes,
      @ModelAttribute templatesRow: DeviceTemplatesRow,
      @RequestParam("settings") settings: String?,
  ): String {
    requirePermissions { updateDeviceTemplates() }

    templatesRow.settings = settings?.ifEmpty { null }?.let { JSONB.jsonb(it) }

    try {
      deviceTemplatesDao.insert(templatesRow)
      redirectAttributes.successMessage = "Device template created."
    } catch (e: Exception) {
      log.error("Failed to create device template", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return deviceTemplates()
  }

  @PostMapping("/deviceManagers")
  fun createDeviceManager(
      redirectAttributes: RedirectAttributes,
      @RequestParam("shortCode") shortCode: String,
  ): String {
    val row =
        DeviceManagersRow(
            balenaId = Random.nextLong(),
            balenaUuid = UUID.randomUUID(),
            balenaModifiedTime = clock.instant(),
            createdTime = clock.instant(),
            deviceName = "Test Device $shortCode",
            isOnline = true,
            refreshedTime = clock.instant(),
            shortCode = shortCode,
        )

    return try {
      deviceManagerStore.insert(row)
      redirectAttributes.successMessage = "Device manager created."

      deviceManager(row.id!!)
    } catch (e: Exception) {
      log.error("Failed to create device manager", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"

      listDeviceManagers()
    }
  }

  @PostMapping("/deviceManagers/{deviceManagerId}")
  fun updateDeviceManager(
      redirectAttributes: RedirectAttributes,
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
      @ModelAttribute row: DeviceManagersRow,
  ): String {
    row.id = deviceManagerId

    if (row.facilityId != null && row.userId == null) {
      row.userId = currentUser().userId
    }

    try {
      val original = deviceManagerStore.fetchOneById(deviceManagerId)!!

      val originalFacilityId = original.facilityId
      val newFacilityId = row.facilityId

      when {
        originalFacilityId != null && newFacilityId == null -> {
          redirectAttributes.failureMessage = "Cannot disconnect a device manager."
          return deviceManager(deviceManagerId)
        }
        originalFacilityId == null && newFacilityId != null -> {
          facilityStore.updateConnectionState(
              newFacilityId,
              FacilityConnectionState.NotConnected,
              FacilityConnectionState.Connected)
        }
        originalFacilityId != newFacilityId -> {
          redirectAttributes.failureMessage =
              "Cannot reassign a device manager to a different facility."
          return deviceManager(deviceManagerId)
        }
      }

      deviceManagerStore.update(row)

      redirectAttributes.successMessage = "Device manager updated."
    } catch (e: Exception) {
      log.error("Failed to update device manager", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return deviceManager(deviceManagerId)
  }

  @PostMapping("/deviceTemplates")
  fun updateTemplate(
      redirectAttributes: RedirectAttributes,
      @ModelAttribute templatesRow: DeviceTemplatesRow,
      @RequestParam("settings") settings: String?,
      @RequestParam("delete") delete: String?,
  ): String {
    requirePermissions { updateDeviceTemplates() }

    templatesRow.settings = settings?.ifEmpty { null }?.let { JSONB.jsonb(it) }

    try {
      if (delete != null) {
        deviceTemplatesDao.deleteById(templatesRow.id)
        redirectAttributes.successMessage = "Device template deleted."
      } else {
        deviceTemplatesDao.update(templatesRow)
        redirectAttributes.successMessage = "Device template updated."
      }
    } catch (e: Exception) {
      log.error("Failed to update device template", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return deviceTemplates()
  }

  @PostMapping("/createDevices")
  fun createDevices(
      redirectAttributes: RedirectAttributes,
      @RequestParam("address") address: String?,
      @RequestParam("count") count: Int,
      @RequestParam("facilityId") facilityId: FacilityId,
      @RequestParam("make") make: String,
      @RequestParam("model") model: String,
      @RequestParam("name") name: String?,
      @RequestParam("protocol") protocol: String?,
      @RequestParam("type") type: String,
  ): String {
    try {
      repeat(count) {
        val calculatedName = name ?: HexUtils.toHexString(Random.nextBytes(4)).uppercase()
        val calculatedAddress = address ?: calculatedName

        val devicesRow =
            DevicesRow(
                address = calculatedAddress,
                deviceType = type,
                facilityId = facilityId,
                make = make,
                model = model,
                name = calculatedName,
                protocol = protocol,
            )

        deviceStore.create(devicesRow)
      }

      redirectAttributes.successMessage =
          if (count == 1) "Device created." else "$count devices created."
    } catch (e: Exception) {
      log.error("Failed to create devices", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return facility(facilityId)
  }

  @InitBinder
  fun initBinder(binder: WebDataBinder) {
    binder.registerCustomEditor(String::class.java, StringTrimmerEditor(true))
    binder.registerCustomEditor(FacilityId::class.java, StringTrimmerEditor(true))
    binder.registerCustomEditor(UserId::class.java, StringTrimmerEditor(true))
  }

  private var RedirectAttributes.failureMessage: String?
    get() = flashAttributes["failureMessage"]?.toString()
    set(value) {
      addFlashAttribute("failureMessage", value)
    }

  private var RedirectAttributes.successMessage: String?
    get() = flashAttributes["successMessage"]?.toString()
    set(value) {
      addFlashAttribute("successMessage", value)
    }

  /** Returns a redirect view name for an admin endpoint. */
  private fun redirect(endpoint: String) = "redirect:${prefix}$endpoint"

  // Convenience methods to redirect to the GET endpoint for each kind of thing.

  private fun adminHome() = redirect("/")
  private fun apiKeyAdded(organizationId: OrganizationId) = redirect("/apiKeyAdded/$organizationId")
  private fun deviceManager(deviceManagerId: DeviceManagerId) =
      redirect("/deviceManagers/$deviceManagerId")
  private fun deviceTemplates() = redirect("/deviceTemplates")
  private fun listDeviceManagers() = redirect("/listDeviceManagers")
  private fun organization(organizationId: OrganizationId) =
      redirect("/organization/$organizationId")
  private fun project(projectId: ProjectId) = redirect("/project/$projectId")
  private fun site(siteId: SiteId) = redirect("/site/$siteId")
  private fun facility(facilityId: FacilityId) = redirect("/facility/$facilityId")
  private fun user(userId: UserId) = redirect("/user/$userId")
}
