package com.terraformation.backend.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.log.perClassLogger
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
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import io.swagger.v3.oas.annotations.Hidden
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminPlantingSitesController(
    private val clock: DatabaseBackedClock,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
    private val organizationsDao: OrganizationsDao,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val plantingSiteImporter: PlantingSiteImporter,
) {
  private val log = perClassLogger()

  @GetMapping("/plantingSite/{plantingSiteId}")
  fun getPlantingSite(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)
    val plotCounts = plantingSiteStore.countMonitoringPlots(plantingSiteId)
    val plantCounts = plantingSiteStore.countReportedPlantsInSubzones(plantingSiteId)
    val organization = organizationStore.fetchOneById(plantingSite.organizationId)
    val reportedPlants = plantingSiteStore.countReportedPlants(plantingSiteId)

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

    val now = clock.instant()
    val (pastPlantingSeasons, futurePlantingSeasons) =
        plantingSite.plantingSeasons.partition { it.endTime < now }

    model.addAttribute("allOrganizations", allOrganizations)
    model.addAttribute("canCreateObservation", currentUser().canCreateObservation(plantingSiteId))
    model.addAttribute("canDeletePlantingSite", currentUser().canDeletePlantingSite(plantingSiteId))
    model.addAttribute("canManageObservations", canManageObservations)
    model.addAttribute("canStartObservations", canStartObservations)
    model.addAttribute(
        "canMovePlantingSiteToAnyOrg", currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId))
    model.addAttribute("canUpdatePlantingSite", currentUser().canUpdatePlantingSite(plantingSiteId))
    model.addAttribute("futurePlantingSeasons", futurePlantingSeasons)
    model.addAttribute("months", months)
    model.addAttribute("nextObservationEnd", nextObservationEnd)
    model.addAttribute("nextObservationStart", nextObservationStart)
    model.addAttribute("numPlantingZones", plantingSite.plantingZones.size)
    model.addAttribute("numSubzones", plantingSite.plantingZones.sumOf { it.plantingSubzones.size })
    model.addAttribute("numPlots", plotCounts.values.flatMap { it.values }.sum())
    model.addAttribute("observations", observations)
    model.addAttribute("observationMessages", observationMessages)
    model.addAttribute("organization", organization)
    model.addAttribute("pastPlantingSeasons", pastPlantingSeasons)
    model.addAttribute("plantCounts", plantCounts)
    model.addAttribute("plotCounts", plotCounts)
    model.addAttribute("reportedPlants", reportedPlants)
    model.addAttribute("site", plantingSite)

    return "/admin/plantingSite"
  }

  @GetMapping("/plantingSite/{plantingSiteId}/map")
  fun getPlantingSiteMap(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)

    model.addAttribute("site", plantingSite)

    if (plantingSite.boundary != null) {
      model.addAttribute("envelope", objectMapper.valueToTree(plantingSite.boundary.envelope))
      model.addAttribute("exclusionGeoJson", objectMapper.valueToTree(plantingSite.exclusion))
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

  @PostMapping("/createPlantingSite", consumes = ["multipart/form-data"])
  fun createPlantingSite(
      @RequestParam organizationId: OrganizationId,
      @RequestParam siteName: String,
      @RequestParam validation: Set<PlantingSiteImporter.ValidationOption>,
      @RequestPart zipfile: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      kotlin.io.path.createTempFile(suffix = ".zip").useAndDelete { localZipFile ->
        zipfile.inputStream.use { inputStream ->
          Files.copy(inputStream, localZipFile, StandardCopyOption.REPLACE_EXISTING)
        }

        val siteId =
            plantingSiteImporter.import(
                siteName, null, organizationId, Shapefile.fromZipFile(localZipFile), validation)

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

    return redirectToOrganization(organizationId)
  }

  @PostMapping("/createPlantingSiteFromMap")
  fun createPlantingSiteFromMap(
      @RequestParam organizationId: OrganizationId,
      @RequestParam siteName: String,
      @RequestParam boundary: String,
      @RequestParam fitSiteToPlots: Boolean,
      @RequestParam siteType: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val boundaryPolygon = objectMapper.readValue<Polygon>(boundary)
      val siteFile = Shapefile.fromBoundary(boundaryPolygon, emptyMap())

      val siteId =
          if (siteType == "detailed") {
            val zonesFile =
                Shapefile.fromBoundary(
                    boundaryPolygon,
                    mapOf(PlantingSiteImporter.zoneNameProperties.first() to "Zone"))
            val subzonesFile =
                Shapefile.fromBoundary(
                    boundaryPolygon,
                    mapOf(
                        PlantingSiteImporter.zoneNameProperties.first() to "Zone",
                        PlantingSiteImporter.subzoneNameProperties.first() to "Subzone"))

            plantingSiteImporter.importShapefiles(
                siteName,
                null,
                organizationId,
                siteFile,
                zonesFile,
                subzonesFile,
                null,
                emptySet(),
                fitSiteToPlots)
          } else {
            plantingSiteStore
                .createPlantingSite(
                    organizationId = organizationId,
                    name = siteName,
                    description = null,
                    timeZone = null,
                    projectId = null,
                    boundary = siteFile.features.first().geometry as MultiPolygon,
                )
                .id
          }

      redirectAttributes.successMessage = "Planting site $siteId imported successfully."

      return redirectToPlantingSite(siteId)
    } catch (e: PlantingSiteUploadProblemsException) {
      log.warn("Site creation failed", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.problems.joinToString()}"
    } catch (e: Exception) {
      log.warn("Site creation failed", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.message}"
    }

    return redirectToOrganization(organizationId)
  }

  @PostMapping("/updatePlantingSite")
  fun updatePlantingSite(
      @RequestParam description: String?,
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam siteName: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      // Retain the existing list of planting seasons, if any.
      val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val updatedSeasons =
          plantingSite.plantingSeasons.map { season ->
            UpdatedPlantingSeasonModel(season.endDate, season.id, season.startDate)
          }

      plantingSiteStore.updatePlantingSite(plantingSiteId, updatedSeasons) { model ->
        model.copy(
            description = description?.ifBlank { null },
            name = siteName,
        )
      }
      redirectAttributes.successMessage = "Planting site updated successfully."
    } catch (e: Exception) {
      log.warn("Planting site update failed", e)
      redirectAttributes.failureMessage = "Planting site update failed: ${e.message}"
    }

    return redirectToPlantingSite(plantingSiteId)
  }

  @PostMapping("/deletePlantingSite")
  fun deletePlantingSite(
      @RequestParam organizationId: OrganizationId,
      @RequestParam plantingSiteId: PlantingSiteId,
      redirectAttributes: RedirectAttributes,
  ): String {
    return try {
      plantingSiteStore.deletePlantingSite(plantingSiteId)
      redirectAttributes.successMessage = "Planting site deleted."
      redirectToOrganization(organizationId)
    } catch (e: Exception) {
      log.warn("Planting site deletion failed", e)
      redirectAttributes.failureMessage = "Planting site deletion failed: ${e.message}"
      redirectToPlantingSite(plantingSiteId)
    }
  }

  @PostMapping("/updatePlantingZone")
  fun updatePlantingZone(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam plantingZoneId: PlantingZoneId,
      @RequestParam variance: BigDecimal,
      @RequestParam errorMargin: BigDecimal,
      @RequestParam studentsT: BigDecimal,
      @RequestParam numPermanent: Int,
      @RequestParam numTemporary: Int,
      @RequestParam targetPlantingDensity: BigDecimal,
      @RequestParam extraPermanent: Int,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.updatePlantingZone(plantingZoneId) { row ->
        row.copy(
            errorMargin = errorMargin,
            extraPermanentClusters = extraPermanent,
            numPermanentClusters = numPermanent,
            numTemporaryPlots = numTemporary,
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )
      }

      redirectAttributes.successMessage = "Planting zone updated successfully."
    } catch (e: Exception) {
      log.warn("Planting zone update failed", e)
      redirectAttributes.failureMessage = "Planting zone update failed: ${e.message}"
    }

    return redirectToPlantingSite(plantingSiteId)
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

      redirectToOrganization(originalOrganizationId)
    } catch (e: Exception) {
      log.warn("Planting site update failed", e)
      redirectAttributes.failureMessage = "Planting site move failed: ${e.message}"

      redirectToPlantingSite(plantingSiteId)
    }
  }

  @PostMapping("/createPlantingSeason")
  fun createPlantingSeason(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam startDate: String,
      @RequestParam endDate: String,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val desiredSeasons =
          site.plantingSeasons.map { UpdatedPlantingSeasonModel(it) } +
              UpdatedPlantingSeasonModel(
                  endDate = LocalDate.parse(endDate), startDate = LocalDate.parse(startDate))

      plantingSiteStore.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      redirectAttributes.successMessage = "Planting season created."
    } catch (e: Exception) {
      log.warn("Planting season creation failed", e)
      redirectAttributes.failureMessage = "Planting season creation failed: ${e.message}"
    }

    return redirectToPlantingSite(plantingSiteId)
  }

  @PostMapping("/deletePlantingSeason")
  fun deletePlantingSeason(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam plantingSeasonId: PlantingSeasonId,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val desiredSeasons =
          site.plantingSeasons
              .filter { it.id != plantingSeasonId }
              .map { UpdatedPlantingSeasonModel(it) }

      plantingSiteStore.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      redirectAttributes.successMessage = "Planting season deleted."
    } catch (e: Exception) {
      log.warn("Planting season deletion failed", e)
      redirectAttributes.failureMessage = "Planting season deletion failed: ${e.message}"
    }

    return redirectToPlantingSite(plantingSiteId)
  }

  @PostMapping("/updatePlantingSeason")
  fun updatePlantingSeason(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam plantingSeasonId: PlantingSeasonId,
      @RequestParam startDate: String,
      @RequestParam endDate: String,
      redirectAttributes: RedirectAttributes
  ): String {
    try {
      val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val desiredSeasons =
          site.plantingSeasons.map { season ->
            if (season.id == plantingSeasonId) {
              UpdatedPlantingSeasonModel(
                  endDate = LocalDate.parse(endDate),
                  id = plantingSeasonId,
                  startDate = LocalDate.parse(startDate))
            } else {
              UpdatedPlantingSeasonModel(season)
            }
          }

      plantingSiteStore.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      redirectAttributes.successMessage = "Planting season updated."
    } catch (e: Exception) {
      log.warn("Planting season update failed", e)
      redirectAttributes.failureMessage = "Planting season update failed: ${e.message}"
    }

    return redirectToPlantingSite(plantingSiteId)
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

    return redirectToPlantingSite(plantingSiteId)
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

    return redirectToPlantingSite(plantingSiteId)
  }

  private fun redirectToOrganization(organizationId: OrganizationId) =
      "redirect:/admin/organization/$organizationId"
  private fun redirectToPlantingSite(plantingSiteId: PlantingSiteId) =
      "redirect:/admin/plantingSite/$plantingSiteId"
}
