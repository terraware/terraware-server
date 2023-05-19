package com.terraformation.backend.customer.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.api.RequireExistingAdminRole
import com.terraformation.backend.api.readString
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.AppVersionStore
import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.InternalTagStore
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.event.FacilityAlertRequestedEvent
import com.terraformation.backend.customer.model.NewFacilityModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.BalenaDeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.DeviceTemplateCategory
import com.terraformation.backend.db.default_schema.FacilityConnectionState
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.db.default_schema.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.default_schema.tables.pojos.AppVersionsRow
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceManagersRow
import com.terraformation.backend.db.default_schema.tables.pojos.DeviceTemplatesRow
import com.terraformation.backend.db.default_schema.tables.pojos.DevicesRow
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.device.DeviceManagerService
import com.terraformation.backend.device.DeviceService
import com.terraformation.backend.device.db.DeviceManagerStore
import com.terraformation.backend.device.db.DeviceStore
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.ReportService
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.render.ReportRenderer
import com.terraformation.backend.species.db.GbifImporter
import com.terraformation.backend.time.DatabaseBackedClock
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteImporter
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.PlantingSiteUploadProblemsException
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.Shapefile
import io.swagger.v3.oas.annotations.Hidden
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.LocalDate
import java.time.Month
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.servlet.http.HttpServletRequest
import javax.validation.constraints.NotBlank
import javax.ws.rs.Produces
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.random.Random
import org.apache.commons.fileupload.FileItemStream
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.apache.tomcat.util.buf.HexUtils
import org.jooq.JSONB
import org.springframework.beans.propertyeditors.StringTrimmerEditor
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireExistingAdminRole
@Validated
class AdminController(
    private val appVersionStore: AppVersionStore,
    private val clock: DatabaseBackedClock,
    private val config: TerrawareServerConfig,
    private val deviceManagerService: DeviceManagerService,
    private val deviceManagerStore: DeviceManagerStore,
    private val deviceService: DeviceService,
    private val deviceStore: DeviceStore,
    private val deviceTemplatesDao: DeviceTemplatesDao,
    private val facilityStore: FacilityStore,
    private val gbifImporter: GbifImporter,
    private val internalTagStore: InternalTagStore,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
    private val organizationsDao: OrganizationsDao,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val plantingSiteImporter: PlantingSiteImporter,
    private val publisher: ApplicationEventPublisher,
    private val reportRenderer: ReportRenderer,
    private val reportService: ReportService,
    private val reportStore: ReportStore,
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
    model.addAttribute("canManageInternalTags", currentUser().canManageInternalTags())
    model.addAttribute("canSetTestClock", config.useTestClock && currentUser().canSetTestClock())
    model.addAttribute("canUpdateAppVersions", currentUser().canUpdateAppVersions())
    model.addAttribute("organizations", organizations)
    model.addAttribute("prefix", prefix)

    return "/admin/index"
  }

  @GetMapping("/organization/{organizationId}")
  fun getOrganization(@PathVariable organizationId: OrganizationId, model: Model): String {
    val organization = organizationStore.fetchOneById(organizationId)
    val facilities = facilityStore.fetchByOrganizationId(organizationId)
    val plantingSites = plantingSiteStore.fetchSitesByOrganizationId(organizationId)
    val reports = reportStore.fetchMetadataByOrganization(organizationId)

    model.addAttribute("canCreateFacility", currentUser().canCreateFacility(organization.id))
    model.addAttribute(
        "canCreatePlantingSite", currentUser().canCreatePlantingSite(organization.id))
    model.addAttribute("canCreateReport", currentUser().userType == UserType.SuperAdmin)
    model.addAttribute("canDeleteReport", currentUser().userType == UserType.SuperAdmin)
    model.addAttribute(
        "canExportReport",
        currentUser().userType == UserType.SuperAdmin && config.report.exportEnabled)
    model.addAttribute("facilities", facilities)
    model.addAttribute("facilityTypes", FacilityType.values())
    model.addAttribute("organization", organization)
    model.addAttribute(
        "plantingSiteValidationOptions", PlantingSiteImporter.ValidationOption.values())
    model.addAttribute("plantingSites", plantingSites)
    model.addAttribute("prefix", prefix)
    model.addAttribute("reports", reports)

    return "/admin/organization"
  }

  @GetMapping("/facility/{facilityId}")
  fun getFacility(@PathVariable facilityId: FacilityId, model: Model): String {
    val facility = facilityStore.fetchOneById(facilityId)
    val organization = organizationStore.fetchOneById(facility.organizationId)
    val recipients = organizationStore.fetchEmailRecipients(facility.organizationId)
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
    model.addAttribute("recipients", recipients)
    model.addAttribute("storageLocations", storageLocations)

    return "/admin/facility"
  }

  @GetMapping("/plantingSite/{plantingSiteId}")
  fun getPlantingSite(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)
    val plotCounts = plantingSiteStore.countMonitoringPlots(plantingSiteId)
    val plantCounts = plantingSiteStore.countReportedPlantsInSubzones(plantingSiteId)
    val organization = organizationStore.fetchOneById(plantingSite.organizationId)

    val allOrganizations =
        if (currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId)) {
          organizationsDao.findAll().sortedBy { it.id!!.value }
        } else {
          null
        }

    val months =
        Month.values().associateWith {
          it.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH)
        }

    val observations = observationStore.fetchObservationsByPlantingSite(plantingSiteId)
    val canManageObservations =
        observations.map { it.id }.associateWith { currentUser().canManageObservation(it) }

    // Explain why an observation doesn't have a "Start" button if it's not obvious from the state.
    val observationMessages =
        observations.associate { observation ->
          observation.id to
              when {
                plantCounts.isEmpty() -> "No reported plants"
                observation.startDate > LocalDate.now(clock) -> "Start date is in future"
                canManageObservations[observation.id] != true ->
                    "No permission to start observation"
                else -> null
              }
        }
    val canStartObservations =
        observations.associate { observation ->
          observation.id to
              (observation.state == ObservationState.Upcoming &&
                  observation.startDate <= LocalDate.now(clock) &&
                  canManageObservations[observation.id] == true &&
                  plantCounts.isNotEmpty())
        }

    val nextObservationStart = plantingSite.getNextObservationStart(clock)
    val nextObservationEnd = nextObservationStart?.plusMonths(1)?.minusDays(1)

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute("canCreateObservation", currentUser().canCreateObservation(plantingSiteId))
    model.addAttribute("canManageObservations", canManageObservations)
    model.addAttribute("canStartObservations", canStartObservations)
    model.addAttribute(
        "canMovePlantingSiteToAnyOrg", currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId))
    model.addAttribute("canUpdatePlantingSite", currentUser().canUpdatePlantingSite(plantingSiteId))
    model.addAttribute("months", months)
    model.addAttribute("nextObservationEnd", nextObservationEnd)
    model.addAttribute("nextObservationStart", nextObservationStart)
    model.addAttribute("numPlantingZones", plantingSite.plantingZones.size)
    model.addAttribute("numSubzones", plantingSite.plantingZones.sumOf { it.plantingSubzones.size })
    model.addAttribute("numPlots", plotCounts.values.flatMap { it.values }.sum())
    model.addAttribute("observations", observations)
    model.addAttribute("observationMessages", observationMessages)
    model.addAttribute("organization", organization)
    model.addAttribute("plantCounts", plantCounts)
    model.addAttribute("plotCounts", plotCounts)
    model.addAttribute("prefix", prefix)
    model.addAttribute("site", plantingSite)

    return "/admin/plantingSite"
  }

  @GetMapping("/plantingSite/{plantingSiteId}/map")
  fun getPlantingSiteMap(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)

    model.addAttribute("prefix", prefix)
    model.addAttribute("site", plantingSite)

    if (plantingSite.boundary != null) {
      model.addAttribute("envelope", objectMapper.valueToTree(plantingSite.boundary.envelope))
      model.addAttribute("siteGeoJson", objectMapper.valueToTree(plantingSite.boundary))
      model.addAttribute("zonesGeoJson", objectMapper.valueToTree(zonesToGeoJson(plantingSite)))
      model.addAttribute(
          "subzonesGeoJson", objectMapper.valueToTree(subzonesToGeoJson(plantingSite)))
      model.addAttribute("mapboxToken", mapboxService.generateTemporaryToken())
    }

    return "/admin/plantingSiteMap"
  }

  @GetMapping("/plantingSite/{plantingSiteId}/plots", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Hidden
  @ResponseBody
  fun getMonitoringPlots(@PathVariable plantingSiteId: PlantingSiteId): Map<String, Any> {
    val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
    val plantedSubzoneIds = plantingSiteStore.countReportedPlantsInSubzones(plantingSiteId).keys

    return plotsToGeoJson(site, plantedSubzoneIds)
  }

  private fun zonesToGeoJson(site: PlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.plantingZones.map { zone ->
                mapOf(
                    "type" to "Feature",
                    "properties" to mapOf("name" to zone.name),
                    "geometry" to zone.boundary,
                )
              })

  private fun subzonesToGeoJson(site: PlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.plantingZones.flatMap { zone ->
                zone.plantingSubzones.map { subzone ->
                  mapOf(
                      "type" to "Feature",
                      "properties" to mapOf("name" to subzone.name),
                      "geometry" to subzone.boundary,
                  )
                }
              })

  private fun plotsToGeoJson(site: PlantingSiteModel, plantedSubzoneIds: Set<PlantingSubzoneId>) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.plantingZones.flatMap { zone ->
                val numPermanent = zone.numPermanentClusters

                zone.plantingSubzones.flatMap { subzone ->
                  val permanentPlotIds =
                      subzone.monitoringPlots
                          .filter {
                            it.permanentCluster != null && it.permanentCluster <= numPermanent
                          }
                          .map { it.id }
                          .toSet()

                  val temporaryPlotIds =
                      zone.chooseTemporaryPlots(permanentPlotIds, plantedSubzoneIds).toSet()

                  subzone.monitoringPlots.map { plot ->
                    val plotTypeProperty =
                        when (plot.id) {
                          in permanentPlotIds -> mapOf("type" to "permanent")
                          in temporaryPlotIds -> mapOf("type" to "temporary")
                          else -> emptyMap()
                        }

                    val cluster =
                        if (plot.permanentCluster != null)
                            "${plot.permanentCluster}-${plot.permanentClusterSubplot}"
                        else null

                    val properties =
                        mapOf(
                            "cluster" to cluster,
                            "id" to "${plot.id}",
                            "name" to plot.fullName,
                            "subzone" to subzone.name,
                            "zone" to zone.name,
                        ) + plotTypeProperty

                    mapOf(
                        "type" to "Feature",
                        "properties" to properties,
                        "geometry" to plot.boundary,
                    )
                  }
                }
              })

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
    model.addAttribute(
        "canRegenerateAllDeviceManagerTokens", currentUser().canRegenerateAllDeviceManagerTokens())
    model.addAttribute("managers", managers)
    model.addAttribute("prefix", prefix)

    return "/admin/listDeviceManagers"
  }

  @GetMapping("/deviceManagers/{deviceManagerId}")
  fun getDeviceManager(
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
      model: Model
  ): String {
    val manager = deviceManagerStore.fetchOneById(deviceManagerId)
    val facility = manager.facilityId?.let { facilityStore.fetchOneById(it) }

    model.addAttribute(
        "canUpdateDeviceManager", currentUser().canUpdateDeviceManager(deviceManagerId))
    model.addAttribute("facility", facility)
    model.addAttribute("manager", manager)
    model.addAttribute("prefix", prefix)

    return "/admin/deviceManager"
  }

  @GetMapping("/testClock")
  fun getTestClock(model: Model, redirectAttributes: RedirectAttributes): String {
    if (!config.useTestClock) {
      redirectAttributes.failureMessage = "Test clock is not enabled."
      return adminHome()
    }

    val now = ZonedDateTime.now(clock)
    val currentTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(now)
    model.addAttribute("currentTime", currentTime)
    model.addAttribute("prefix", prefix)

    return "/admin/testClock"
  }

  @GetMapping("/appVersions")
  fun getAppVersions(model: Model, redirectAttributes: RedirectAttributes): String {
    requirePermissions { updateAppVersions() }

    val versions = appVersionStore.findAll()
    model.addAttribute("appVersions", versions)
    model.addAttribute("prefix", prefix)

    return "/admin/appVersions"
  }

  @GetMapping("/internalTags")
  fun listInternalTags(model: Model, redirectAttributes: RedirectAttributes): String {
    val tags = internalTagStore.findAllTags()
    val allOrganizations = organizationsDao.findAll().sortedBy { it.id!!.value }
    val organizationTags = internalTagStore.fetchAllOrganizationTagIds()

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute("organizationTags", organizationTags)
    model.addAttribute("prefix", prefix)
    model.addAttribute("tags", tags)
    model.addAttribute("tagsById", tags.associateBy { it.id!! })

    return "/admin/listInternalTags"
  }

  @GetMapping("/internalTag/{id}")
  fun getInternalTag(
      @PathVariable("id") tagId: InternalTagId,
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    val tag = internalTagStore.fetchTagById(tagId)
    val organizations = internalTagStore.fetchOrganizationsByTagId(tagId)

    model.addAttribute("organizations", organizations)
    model.addAttribute("prefix", prefix)
    model.addAttribute("tag", tag)

    return "/admin/internalTag"
  }

  @GetMapping("/report/{id}/index.html")
  @Produces("text/html")
  fun getReportHtml(@PathVariable("id") reportId: ReportId): ResponseEntity<String> {
    return ResponseEntity.ok(reportRenderer.renderReportHtml(reportId))
  }

  @GetMapping("/report/{id}/report.csv")
  @Produces("text/csv")
  fun getReportCsv(@PathVariable("id") reportId: ReportId): ResponseEntity<String> {
    return ResponseEntity.ok()
        .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(reportRenderer.renderReportCsv(reportId))
  }

  @PostMapping("/createFacility")
  fun createFacility(
      @RequestParam organizationId: OrganizationId,
      @NotBlank @RequestParam name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.failureMessage = "Unknown facility type."
      return organization(organizationId)
    }

    facilityStore.create(
        NewFacilityModel(name = name, organizationId = organizationId, type = type))

    redirectAttributes.successMessage = "Facility created."

    return organization(organizationId)
  }

  @PostMapping("/updateFacility")
  fun updateFacility(
      @RequestParam connectionState: FacilityConnectionState,
      @RequestParam description: String?,
      @RequestParam facilityId: FacilityId,
      @RequestParam maxIdleMinutes: Int,
      @RequestParam name: String,
      @RequestParam("type") typeId: Int,
      redirectAttributes: RedirectAttributes
  ): String {
    val type = FacilityType.forId(typeId)

    if (type == null) {
      redirectAttributes.failureMessage = "Unknown facility type."
      return facility(facilityId)
    }

    val existing = facilityStore.fetchOneById(facilityId)
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
      @RequestParam facilityId: FacilityId,
      @RequestParam subject: String,
      @RequestParam body: String,
      redirectAttributes: RedirectAttributes
  ): String {
    if (facilityStore.fetchOneById(facilityId).connectionState !=
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
      @RequestParam facilityId: FacilityId,
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes
  ): String {
    facilityStore.createStorageLocation(facilityId, name)

    redirectAttributes.successMessage = "Storage location created."

    return facility(facilityId)
  }

  @PostMapping("/updateStorageLocation")
  fun updateStorageLocation(
      @RequestParam facilityId: FacilityId,
      @RequestParam storageLocationId: StorageLocationId,
      @RequestParam name: String,
      redirectAttributes: RedirectAttributes
  ): String {
    facilityStore.updateStorageLocation(storageLocationId, name)

    redirectAttributes.successMessage = "Storage location updated."

    return facility(facilityId)
  }

  @PostMapping("/deleteStorageLocation")
  fun deleteStorageLocation(
      @RequestParam facilityId: FacilityId,
      @RequestParam storageLocationId: StorageLocationId,
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
      @RequestParam settings: String?,
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
      @RequestParam sensorKitId: String,
  ): String {
    val row =
        DeviceManagersRow(
            balenaId = BalenaDeviceId(Random.nextLong()),
            balenaUuid = UUID.randomUUID().toString(),
            balenaModifiedTime = clock.instant(),
            createdTime = clock.instant(),
            deviceName = "Test Device $sensorKitId",
            isOnline = true,
            refreshedTime = clock.instant(),
            sensorKitId = sensorKitId,
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
      val original = deviceManagerStore.fetchOneById(deviceManagerId)

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

  @PostMapping("/deviceManagers/{deviceManagerId}/generateToken")
  fun generateToken(
      redirectAttributes: RedirectAttributes,
      @PathVariable("deviceManagerId") deviceManagerId: DeviceManagerId,
  ): String {
    try {
      val row = deviceManagerStore.fetchOneById(deviceManagerId)
      val balenaId = row.balenaId ?: throw IllegalStateException("Device manager has no balena ID")
      val facilityId =
          row.facilityId
              ?: throw IllegalStateException("Device manager is not connected to a facility")
      val userId =
          row.userId ?: throw IllegalStateException("Device manager does not have a user ID")

      deviceManagerService.generateOfflineToken(userId, balenaId, facilityId, overwrite = true)

      redirectAttributes.successMessage = "Token generated."
    } catch (e: Exception) {
      log.error("Failed to generate refresh token", e)
      redirectAttributes.failureMessage = "Generation failed: ${e.message}"
    }

    return deviceManager(deviceManagerId)
  }

  @PostMapping("/deviceManagers/regenerateAllTokens")
  fun regenerateAllTokens(
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      deviceManagerService.regenerateAllOfflineTokens()

      redirectAttributes.successMessage = "Tokens regenerated."
    } catch (e: Exception) {
      log.error("Failed to regenerate refresh tokens", e)
      redirectAttributes.failureMessage = "Generation failed: ${e.message}"
    }

    return listDeviceManagers()
  }

  @PostMapping("/deviceTemplates")
  fun updateTemplate(
      redirectAttributes: RedirectAttributes,
      @ModelAttribute templatesRow: DeviceTemplatesRow,
      @RequestParam settings: String?,
      @RequestParam delete: String?,
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
      @RequestParam address: String?,
      @RequestParam count: Int,
      @RequestParam facilityId: FacilityId,
      @RequestParam make: String,
      @RequestParam model: String,
      @RequestParam name: String?,
      @RequestParam protocol: String?,
      @RequestParam type: String,
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

        deviceService.create(devicesRow)
      }

      redirectAttributes.successMessage =
          if (count == 1) "Device created." else "$count devices created."
    } catch (e: Exception) {
      log.error("Failed to create devices", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return facility(facilityId)
  }

  @PostMapping("/advanceTestClock")
  fun advanceTestClock(
      @RequestParam quantity: Long,
      @RequestParam units: String,
      redirectAttributes: RedirectAttributes
  ): String {
    val duration =
        when (units) {
          "M" -> Duration.ofMinutes(quantity)
          "H" -> Duration.ofHours(quantity)
          "D" -> Duration.ofDays(quantity)
          else -> Duration.parse("PT$quantity$units")
        }
    val prettyDuration =
        when (units) {
          "M" -> if (quantity == 1L) "1 minute" else "$quantity minutes"
          "H" -> if (quantity == 1L) "1 hour" else "$quantity hours"
          "D" -> if (quantity == 1L) "1 day" else "$quantity days"
          else -> "$duration"
        }

    clock.advance(duration)

    redirectAttributes.successMessage = "Clock advanced by $prettyDuration."
    return testClock()
  }

  @PostMapping("/resetTestClock")
  fun resetTestClock(redirectAttributes: RedirectAttributes): String {
    clock.reset()
    redirectAttributes.successMessage = "Test clock reset to real time and date."
    return testClock()
  }

  @PostMapping("/createAppVersion")
  fun createAppVersion(
      @RequestParam appName: String,
      @RequestParam platform: String,
      @RequestParam minimumVersion: String,
      @RequestParam recommendedVersion: String,
      redirectAttributes: RedirectAttributes
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

    return appVersions()
  }

  @PostMapping("/updateAppVersion")
  fun updateAppVersion(
      @RequestParam originalAppName: String,
      @RequestParam originalPlatform: String,
      @RequestParam appName: String,
      @RequestParam platform: String,
      @RequestParam minimumVersion: String,
      @RequestParam recommendedVersion: String,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      appVersionStore.update(
          AppVersionsRow(originalAppName, originalPlatform),
          AppVersionsRow(appName, platform, minimumVersion, recommendedVersion))
      redirectAttributes.successMessage = "App version updated."
    } catch (e: DuplicateKeyException) {
      // User edited the appName/platform to collide with an existing one.
      redirectAttributes.failureMessage = "An entry for that app name and platform already exists."
    } catch (e: Exception) {
      log.error("Failed to update app version", e)
      redirectAttributes.failureMessage = "Update failed: ${e.message}"
    }

    return appVersions()
  }

  @PostMapping("/deleteAppVersion")
  fun deleteAppVersion(
      @RequestParam appName: String,
      @RequestParam platform: String,
      redirectAttributes: RedirectAttributes
  ): String {
    appVersionStore.delete(appName, platform)
    redirectAttributes.successMessage = "App version deleted."
    return appVersions()
  }

  @PostMapping("/createPlantingSite", consumes = ["multipart/form-data"])
  fun createPlantingSite(
      request: HttpServletRequest,
      redirectAttributes: RedirectAttributes,
  ): String {
    var name: String? = null
    var organizationId: OrganizationId? = null

    try {
      val formItems = ServletFileUpload().getItemIterator(request)

      createTempFile(suffix = ".zip").useAndDelete { zipFilePath ->
        val validationOptions = mutableSetOf<PlantingSiteImporter.ValidationOption>()

        while (formItems.hasNext()) {
          val item: FileItemStream = formItems.next()

          when (item.fieldName) {
            "organizationId" -> organizationId = OrganizationId(item.readString())
            "siteName" -> name = item.readString()
            "validation" -> {
              validationOptions.add(
                  PlantingSiteImporter.ValidationOption.valueOf(item.readString()))
            }
            "zipfile" -> {
              item.openStream().use { inputStream ->
                Files.copy(inputStream, zipFilePath, StandardCopyOption.REPLACE_EXISTING)
              }
            }
          }
        }

        val siteId =
            plantingSiteImporter.import(
                name ?: throw IllegalArgumentException("Missing planting site name"),
                null,
                organizationId ?: throw IllegalArgumentException("Missing organization ID"),
                Shapefile.fromZipFile(zipFilePath),
                validationOptions)

        redirectAttributes.successMessage = "Planting site $siteId imported successfully."
      }
    } catch (e: PlantingSiteUploadProblemsException) {
      log.warn("Shapefile import failed validation: ${e.problems}")
      redirectAttributes.failureMessage = "Uploaded file failed validation checks"
      redirectAttributes.failureDetails = e.problems
    } catch (e: Exception) {
      log.warn("Shapefile import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return organizationId?.let { organization(it) } ?: adminHome()
  }

  @PostMapping("/updatePlantingSite")
  fun updatePlantingSite(
      @RequestParam description: String?,
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam plantingSeasonEndMonth: Month?,
      @RequestParam plantingSeasonStartMonth: Month?,
      @RequestParam siteName: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.updatePlantingSite(plantingSiteId) { model ->
        model.copy(
            description = description?.ifBlank { null },
            name = siteName,
            plantingSeasonEndMonth = plantingSeasonEndMonth,
            plantingSeasonStartMonth = plantingSeasonStartMonth,
        )
      }
      redirectAttributes.successMessage = "Planting site updated successfully."
    } catch (e: Exception) {
      log.warn("Planting site update failed", e)
      redirectAttributes.failureMessage = "Planting site update failed: ${e.message}"
    }

    return plantingSite(plantingSiteId)
  }

  @PostMapping("/updatePlantingZone")
  fun updatePlantingZone(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam plantingZoneId: PlantingZoneId,
      @RequestParam variance: BigDecimal?,
      @RequestParam errorMargin: BigDecimal?,
      @RequestParam studentsT: BigDecimal?,
      @RequestParam numPermanent: Int?,
      @RequestParam numTemporary: Int?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.updatePlantingZone(plantingZoneId) { row ->
        row.copy(
            errorMargin = errorMargin,
            numPermanentClusters = numPermanent,
            numTemporaryPlots = numTemporary,
            studentsT = studentsT,
            variance = variance,
        )
      }

      redirectAttributes.successMessage = "Planting zone updated successfully."
    } catch (e: Exception) {
      log.warn("Planting zone update failed", e)
      redirectAttributes.failureMessage = "Planting zone update failed: ${e.message}"
    }

    return plantingSite(plantingSiteId)
  }

  @PostMapping("/movePlantingSite")
  fun movePlantingSite(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam organizationId: OrganizationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    return try {
      val originalOrganizationId =
          plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site).organizationId

      plantingSiteStore.movePlantingSite(plantingSiteId, organizationId)
      redirectAttributes.successMessage =
          "Planting site $plantingSiteId moved to organization $organizationId. Returning to " +
              "original organization."

      organization(originalOrganizationId)
    } catch (e: Exception) {
      log.warn("Planting site update failed", e)
      redirectAttributes.failureMessage = "Planting site move failed: ${e.message}"

      plantingSite(plantingSiteId)
    }
  }

  @PostMapping("/createObservation")
  fun createObservation(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam startDate: String,
      @RequestParam endDate: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val observationId =
          observationStore.createObservation(
              NewObservationModel(
                  endDate = LocalDate.parse(endDate),
                  id = null,
                  plantingSiteId = plantingSiteId,
                  startDate = LocalDate.parse(startDate),
                  state = ObservationState.Upcoming))

      redirectAttributes.successMessage = "Created observation $observationId"
    } catch (e: Exception) {
      log.warn("Observation creation failed", e)
      redirectAttributes.failureMessage = "Observation creation failed: ${e.message}"
    }

    return plantingSite(plantingSiteId)
  }

  @PostMapping("/startObservation")
  fun startObservation(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam observationId: ObservationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      observationService.startObservation(observationId)
    } catch (e: Exception) {
      log.warn("Observation start failed", e)
      redirectAttributes.failureMessage = "Failed to start observation: ${e.message}"
    }

    return plantingSite(plantingSiteId)
  }

  @PostMapping("/createReport")
  fun createReport(
      @RequestParam organizationId: OrganizationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val metadata = reportService.create(organizationId)
      redirectAttributes.successMessage = "Report ${metadata.id} created."
    } catch (e: Exception) {
      log.warn("Report creation failed", e)
      redirectAttributes.failureMessage = "Report creation failed: ${e.message}"
    }

    return organization(organizationId)
  }

  @PostMapping("/deleteReport")
  fun deleteReport(
      @RequestParam organizationId: OrganizationId,
      @RequestParam reportId: ReportId,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { deleteReport(reportId) }

    try {
      reportStore.delete(reportId)
      redirectAttributes.successMessage = "Deleted report."
    } catch (e: Exception) {
      log.warn("Report deletion failed", e)
      redirectAttributes.failureMessage = "Report deletion failed: ${e.message}"
    }

    return organization(organizationId)
  }

  @PostMapping("/exportReport")
  fun exportReport(
      @RequestParam organizationId: OrganizationId,
      @RequestParam reportId: ReportId,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { createReport(organizationId) }

    try {
      reportService.exportToGoogleDrive(reportId)
      redirectAttributes.successMessage = "Exported report to Google Drive."
    } catch (e: Exception) {
      log.warn("Report export failed", e)
      redirectAttributes.failureMessage = "Report export failed: ${e.message}"
    }

    return organization(organizationId)
  }

  @PostMapping("/createInternalTag")
  fun createInternalTag(
      @RequestParam name: String,
      @RequestParam description: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.createTag(name, description?.ifEmpty { null })
      redirectAttributes.successMessage = "Tag $name created."
    } catch (e: DuplicateKeyException) {
      redirectAttributes.failureMessage = "A tag by that name already exists."
    } catch (e: Exception) {
      log.warn("Tag creation failed", e)
      redirectAttributes.failureMessage = "Tag creation failed: ${e.message}"
    }

    return internalTags()
  }

  @PostMapping("/updateInternalTag/{id}")
  fun updateInternalTag(
      @PathVariable("id") id: InternalTagId,
      @RequestParam name: String,
      @RequestParam description: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.updateTag(id, name, description)
      redirectAttributes.successMessage = "Tag updated."
    } catch (e: Exception) {
      log.warn("Tag update failed", e)
      redirectAttributes.failureMessage = "Tag update failed: ${e.message}"
    }

    return internalTag(id)
  }

  @PostMapping("/deleteInternalTag/{id}")
  fun deleteInternalTag(
      @PathVariable("id") id: InternalTagId,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      internalTagStore.deleteTag(id)
      redirectAttributes.successMessage = "Tag deleted."
    } catch (e: Exception) {
      log.warn("Tag deletion failed", e)
      redirectAttributes.failureMessage = "Tag deletion failed: ${e.message}"
    }

    return internalTags()
  }

  @PostMapping("/updateOrganizationInternalTags")
  fun updateOrganizationInternalTags(
      @RequestParam organizationId: OrganizationId,
      @RequestParam("tagId", required = false) tagIds: Set<InternalTagId>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      internalTagStore.updateOrganizationTags(organizationId, tagIds ?: emptySet())
      redirectAttributes.successMessage = "Tags for organization $organizationId updated."
    } catch (e: Exception) {
      log.warn("Organization tag update failed", e)
      redirectAttributes.failureMessage = "Organization tag update failed: ${e.message}"
    }

    return internalTags()
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

  private var RedirectAttributes.failureDetails: List<String>?
    get() {
      val attribute = flashAttributes["failureDetails"]
      return if (attribute is List<*>) {
        attribute.map { "$it" }
      } else {
        null
      }
    }
    set(value) {
      addFlashAttribute("failureDetails", value)
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
  private fun appVersions() = redirect("/appVersions")
  private fun deviceManager(deviceManagerId: DeviceManagerId) =
      redirect("/deviceManagers/$deviceManagerId")
  private fun deviceTemplates() = redirect("/deviceTemplates")
  private fun listDeviceManagers() = redirect("/deviceManagers")
  private fun organization(organizationId: OrganizationId) =
      redirect("/organization/$organizationId")
  private fun plantingSite(plantingSiteId: PlantingSiteId) =
      redirect("/plantingSite/$plantingSiteId")
  private fun facility(facilityId: FacilityId) = redirect("/facility/$facilityId")
  private fun testClock() = redirect("/testClock")
  private fun internalTag(tagId: InternalTagId) = redirect("/internalTag/$tagId")
  private fun internalTags() = redirect("/internalTags")
}
