package com.terraformation.backend.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.CountriesDao
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.file.useAndDelete
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.time.DatabaseBackedClock
import com.terraformation.backend.tracking.ObservationService
import com.terraformation.backend.tracking.db.DeliveryStore
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteImporter
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.db.ShapefilesInvalidException
import com.terraformation.backend.tracking.edit.MonitoringPlotEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEdit
import com.terraformation.backend.tracking.edit.PlantingSiteEditCalculator
import com.terraformation.backend.tracking.edit.PlantingSubzoneEdit
import com.terraformation.backend.tracking.edit.PlantingZoneEdit
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneFullException
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.nearlyCoveredBy
import com.terraformation.backend.util.toMultiPolygon
import io.swagger.v3.oas.annotations.Hidden
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel
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
    private val countriesDao: CountriesDao,
    private val deliveryStore: DeliveryStore,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
    private val observationService: ObservationService,
    private val observationStore: ObservationStore,
    private val organizationsDao: OrganizationsDao,
    private val organizationStore: OrganizationStore,
    private val plantingSiteStore: PlantingSiteStore,
    private val plantingSiteImporter: PlantingSiteImporter,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @GetMapping("/plantingSite/{plantingSiteId}")
  fun getPlantingSite(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Subzone)
    val country = plantingSite.countryCode?.let { countriesDao.fetchOneByCode(it) }
    val plotCounts = plantingSiteStore.countMonitoringPlots(plantingSiteId)
    val plantCounts = plantingSiteStore.countReportedPlantsInSubzones(plantingSiteId)
    val organization = organizationStore.fetchOneById(plantingSite.organizationId)
    val reportedPlants = plantingSiteStore.countReportedPlants(plantingSiteId)
    val subzonesById =
        plantingSite.plantingZones
            .flatMap { it.plantingSubzones }
            .sortedBy { it.fullName }
            .associateBy { it.id }

    val allOrganizations =
        if (currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId)) {
          organizationsDao.findAll().sortedBy { it.id }
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
        "canMovePlantingSiteToAnyOrg",
        currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId),
    )
    model.addAttribute("canUpdatePlantingSite", currentUser().canUpdatePlantingSite(plantingSiteId))
    model.addAttribute("country", country)
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
    model.addAttribute("subzonesById", subzonesById)

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
          "subzonesGeoJson",
          objectMapper.valueToTree(subzonesToGeoJson(plantingSite)),
      )
      model.addAttribute("mapboxToken", mapboxService.generateTemporaryToken())
    }

    return "/admin/plantingSiteMap"
  }

  @GetMapping("/plantingSite/{plantingSiteId}/plots", produces = [MediaType.APPLICATION_JSON_VALUE])
  @Hidden
  @ResponseBody
  fun getMonitoringPlots(@PathVariable plantingSiteId: PlantingSiteId): Map<String, Any> {
    val site = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    return plotsToGeoJson(site)
  }

  private fun zonesToGeoJson(site: ExistingPlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.plantingZones.map { zone ->
                mapOf(
                    "type" to "Feature",
                    "properties" to mapOf("name" to zone.name),
                    "geometry" to zone.boundary,
                )
              },
      )

  private fun subzonesToGeoJson(site: ExistingPlantingSiteModel) =
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
              },
      )

  private fun plotsToGeoJson(site: ExistingPlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.plantingZones.flatMap { zone ->
                val numPermanent = zone.numPermanentPlots

                val temporaryPlotsAndIds: List<Pair<Polygon, MonitoringPlotId?>> =
                    try {
                      zone
                          .chooseTemporaryPlots(
                              site.plantingZones
                                  .flatMap { zone -> zone.plantingSubzones.map { it.id } }
                                  .toSet(),
                              gridOrigin = site.gridOrigin!!,
                              exclusion = site.exclusion)
                          .map { boundary -> boundary to zone.findMonitoringPlot(boundary)?.id }
                    } catch (e: PlantingSubzoneFullException) {
                      emptyList()
                    }
                val temporaryPlotIds = temporaryPlotsAndIds.mapNotNull { (_, id) -> id }.toSet()

                // Temporary plots that, if this were the start of an actual observation, we would
                // need to create in the database.
                val newTemporaryPlots =
                    temporaryPlotsAndIds
                        .filter { (_, id) -> id == null }
                        .map { (plotBoundary, _) ->
                          mapOf(
                              "type" to "Feature",
                              "geometry" to plotBoundary,
                              "properties" to
                                  mapOf(
                                      "id" to "(new)",
                                      "name" to "New Temporary Plot",
                                      "permanentIndex" to null,
                                      "subzone" to zone.findPlantingSubzone(plotBoundary)?.name,
                                      "type" to "temporary",
                                      "zone" to zone.name,
                                  ),
                          )
                        }

                val existingPlots =
                    zone.plantingSubzones.flatMap { subzone ->
                      val permanentPlotIds =
                          subzone.monitoringPlots
                              .filter {
                                it.permanentIndex != null && it.permanentIndex <= numPermanent
                              }
                              .map { it.id }
                              .toSet()

                      subzone.monitoringPlots.map { plot ->
                        val plotTypeProperty =
                            when (plot.id) {
                              in permanentPlotIds -> mapOf("type" to "permanent")
                              in temporaryPlotIds -> mapOf("type" to "temporary")
                              else -> emptyMap()
                            }

                        val permanentIndex = plot.permanentIndex?.toString()

                        val properties =
                            mapOf(
                                "id" to "${plot.id}",
                                "permanentIndex" to permanentIndex,
                                "plotNumber" to plot.plotNumber,
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

                existingPlots + newTemporaryPlots
              },
      )

  @PostMapping("/createPlantingSite", consumes = ["multipart/form-data"])
  fun createPlantingSite(
      @RequestParam organizationId: OrganizationId,
      @RequestParam siteName: String,
      @RequestParam gridOriginLat: Double?,
      @RequestParam gridOriginLong: Double?,
      @RequestPart zipfile: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      kotlin.io.path.createTempFile(suffix = ".zip").useAndDelete { localZipFile ->
        zipfile.inputStream.use { inputStream ->
          Files.copy(inputStream, localZipFile, StandardCopyOption.REPLACE_EXISTING)
        }

        val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
        val gridOrigin =
            if (gridOriginLat != null && gridOriginLong != null) {
              geometryFactory.createPoint(Coordinate(gridOriginLong, gridOriginLat))
            } else {
              null
            }

        val siteId =
            plantingSiteImporter.import(
                siteName,
                null,
                organizationId,
                Shapefile.fromZipFile(localZipFile),
                gridOrigin,
            )

        redirectAttributes.successMessage = "Planting site $siteId imported successfully."
      }
    } catch (e: PlantingSiteMapInvalidException) {
      log.warn("Shapefile import failed validation: ${e.problems}")
      redirectAttributes.failureMessage = "Uploaded file failed validation checks"
      redirectAttributes.failureDetails = e.problems.map { "$it" }
    } catch (e: ShapefilesInvalidException) {
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
      @RequestParam siteType: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val siteBoundary = objectMapper.readValue<Polygon>(boundary).toMultiPolygon()

      val siteId =
          if (siteType == "detailed") {
            val subzonesFile =
                Shapefile.fromBoundary(
                    siteBoundary,
                    mapOf(
                        PlantingSiteImporter.zoneNameProperties.first() to "Zone",
                        PlantingSiteImporter.subzoneNameProperties.first() to "Subzone",
                    ),
                )

            plantingSiteImporter.import(siteName, null, organizationId, listOf(subzonesFile))
          } else {
            plantingSiteStore
                .createPlantingSite(
                    PlantingSiteModel.create(
                        boundary = siteBoundary,
                        name = siteName,
                        organizationId = organizationId,
                    ),
                )
                .id
          }

      redirectAttributes.successMessage = "Planting site $siteId imported successfully."

      return redirectToPlantingSite(siteId)
    } catch (e: PlantingSiteMapInvalidException) {
      log.warn("Site creation failed", e)
      redirectAttributes.failureMessage = "Creation failed: ${e.problems.joinToString()}"
    } catch (e: ShapefilesInvalidException) {
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

  @PostMapping("/updatePlantingSiteShapefiles", consumes = ["multipart/form-data"])
  fun updatePlantingSiteShapefiles(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam dryRun: Boolean,
      @RequestParam subzoneIdsToMarkIncomplete: String?,
      @RequestPart zipfile: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      kotlin.io.path.createTempFile(suffix = ".zip").useAndDelete { localZipFile ->
        zipfile.inputStream.use { inputStream ->
          Files.copy(inputStream, localZipFile, StandardCopyOption.REPLACE_EXISTING)
        }

        val existing = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)
        val desired =
            plantingSiteImporter.shapefilesToModel(
                Shapefile.fromZipFile(localZipFile),
                existing.name,
                existing.description,
                existing.organizationId,
                requireStableIds = true,
            )

        val calculator = PlantingSiteEditCalculator(existing, desired)

        val edit = calculator.calculateSiteEdit()

        if (dryRun) {
          redirectAttributes.successMessage = "Would make the following changes:"
          redirectAttributes.successDetails = describeSiteEdit(edit)
        } else {
          plantingSiteStore.applyPlantingSiteEdit(
              edit,
              subzoneIdsToMarkIncomplete?.split(",")?.map { PlantingSubzoneId(it.trim()) }?.toSet()
                  ?: emptySet(),
          )
          redirectAttributes.successMessage = "Site map updated."
        }
      }
    } catch (e: PlantingSiteMapInvalidException) {
      log.warn("Shapefile import failed validation: ${e.problems}")
      redirectAttributes.failureMessage = "Uploaded file failed validation checks"
      redirectAttributes.failureDetails = e.problems.map { "$it" }
    } catch (e: ShapefilesInvalidException) {
      log.warn("Shapefile import failed validation: ${e.problems}")
      redirectAttributes.failureMessage = "Uploaded file failed validation checks"
      redirectAttributes.failureDetails = e.problems
    } catch (e: Exception) {
      log.warn("Shapefile import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
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
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.updatePlantingZone(plantingZoneId) { row ->
        row.copy(
            errorMargin = errorMargin,
            numPermanentPlots = numPermanent,
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
                  endDate = LocalDate.parse(endDate),
                  startDate = LocalDate.parse(startDate),
              )

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
                  startDate = LocalDate.parse(startDate),
              )
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
      @RequestParam requestedSubzoneIds: Set<PlantingSubzoneId>,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val observationId =
          observationStore.createObservation(
              NewObservationModel(
                  endDate = LocalDate.parse(endDate),
                  id = null,
                  isAdHoc = false,
                  observationType = ObservationType.Monitoring,
                  plantingSiteId = plantingSiteId,
                  requestedSubzoneIds = requestedSubzoneIds,
                  startDate = LocalDate.parse(startDate),
                  state = ObservationState.Upcoming,
              ),
          )

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

  @PostMapping("/recalculateMortalityRates")
  fun recalculateMortalityRates(
      @RequestParam observationId: ObservationId,
      @RequestParam plantingSiteId: PlantingSiteId,
      redirectAttributes: RedirectAttributes
  ): String {
    requirePermissions { manageObservation(observationId) }

    try {
      observationStore.recalculateMortalityRates(observationId, plantingSiteId)
      redirectAttributes.successMessage =
          "Recalculated mortality rates for observation $observationId."
    } catch (e: Exception) {
      log.warn("Mortality rate recalculation failed", e)
      redirectAttributes.failureMessage = "Failed to recalculate mortality rates: ${e.message}"
    }

    return redirectToAdminHome()
  }

  @PostMapping("/recalculatePopulations")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun recalculatePopulations(
      @RequestParam plantingSiteId: PlantingSiteId,
      redirectAttributes: RedirectAttributes
  ): String {

    try {
      deliveryStore.recalculatePopulationsFromPlantings(plantingSiteId)
      redirectAttributes.successMessage = "Recalculated populations."
    } catch (e: Exception) {
      log.error("Failed to recalculate populations", e)
      redirectAttributes.failureMessage = "Failed to recalculate populations: ${e.message}"
    }

    return redirectToAdminHome()
  }

  @PostMapping("/migrateSimplePlantingSites")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun migrateSimplePlantingSites(redirectAttributes: RedirectAttributes): String {
    val successDetails = mutableListOf<String>()
    val failureDetails = mutableListOf<String>()

    try {
      systemUser.run {
        plantingSiteStore.migrateSimplePlantingSites(successDetails::add, failureDetails::add)
        redirectAttributes.successMessage = "Successfully migrated planting sites"
        redirectAttributes.successDetails = successDetails
      }

      if (failureDetails.isNotEmpty()) {
        redirectAttributes.failureMessage = "Failed to migrate some planting sites"
        redirectAttributes.failureDetails = failureDetails
      }
    } catch (e: Exception) {
      log.error("Failed to migrate simple planting sites", e)
      redirectAttributes.failureMessage = "Failed to migrate planting sites: ${e.message}"
    }

    return redirectToAdminHome()
  }

  private fun describeSiteEdit(edit: PlantingSiteEdit): List<String> {
    log.info("Site edit ${objectMapper.writeValueAsString(edit)}")
    val zoneChanges = edit.plantingZoneEdits.flatMap { zoneEdit -> describeZoneEdit(zoneEdit) }

    val affectedObservationIds =
        observationStore.fetchActiveObservationIds(
            edit.existingModel.id, edit.plantingZoneEdits.mapNotNull { it.existingModel?.id })
    val affectedObservationsMessage =
        if (affectedObservationIds.isNotEmpty()) {
          "The following observations will be abandoned because they have incomplete " +
              "plots in edited planting zones: " +
              affectedObservationIds.joinToString(", ")
        } else {
          null
        }

    return listOfNotNull(
        affectedObservationsMessage,
        "Total change in plantable area: ${edit.areaHaDifference.toPlainString()}ha",
    ) + zoneChanges
  }

  private fun describeZoneEdit(zoneEdit: PlantingZoneEdit): List<String> {
    val desiredModel = zoneEdit.desiredModel
    val existingModel = zoneEdit.existingModel
    val existingBoundary = existingModel?.boundary
    val zoneName = existingModel?.name ?: desiredModel!!.name
    val prefix = "Zone $zoneName:"
    val monitoringPlotCreations =
        zoneEdit.monitoringPlotEdits.map { plotEdit ->
          val existingOrNew =
              if (existingBoundary != null && plotEdit.region.nearlyCoveredBy(existingBoundary)) {
                "existing"
              } else {
                "new"
              }
          "$prefix Create permanent plot index ${plotEdit.permanentIndex} in $existingOrNew area"
        }
    val subzoneEdits = zoneEdit.plantingSubzoneEdits.flatMap { describeSubzoneEdit(zoneEdit, it) }

    return listOf(
        if (existingModel != null) {
          if (desiredModel != null) {
            val overlapPercent = renderOverlapPercent(existingModel.boundary, desiredModel.boundary)
            val areaDifference = zoneEdit.areaHaDifference.toPlainString()
            "$prefix Change in plantable area: ${areaDifference}ha, overlap $overlapPercent%"
          } else {
            "$prefix Delete (stable ID ${existingModel.stableId})"
          }
        } else {
          "$prefix Create (stable ID ${desiredModel?.stableId})"
        }) + monitoringPlotCreations + subzoneEdits
  }

  private fun describeSubzoneEdit(
      zoneEdit: PlantingZoneEdit,
      subzoneEdit: PlantingSubzoneEdit
  ): List<String> {
    val zoneName = zoneEdit.existingModel?.name ?: zoneEdit.desiredModel!!.name
    val subzoneName = subzoneEdit.existingModel?.name ?: subzoneEdit.desiredModel!!.name
    val prefix = "Zone $zoneName subzone $subzoneName:"
    val subzoneEditText =
        when (subzoneEdit) {
          is PlantingSubzoneEdit.Create ->
              "$prefix Create (stable ID ${subzoneEdit.desiredModel.stableId})"
          is PlantingSubzoneEdit.Delete ->
              "$prefix Delete (stable ID ${subzoneEdit.existingModel.stableId})"
          is PlantingSubzoneEdit.Update -> {
            val overlapPercent =
                renderOverlapPercent(
                    subzoneEdit.existingModel.boundary, subzoneEdit.desiredModel.boundary)
            val areaDifference = subzoneEdit.areaHaDifference.toPlainString()
            "$prefix Change in plantable area: ${areaDifference}ha, overlap $overlapPercent%"
          }
        }
    val monitoringPlotEdits =
        subzoneEdit.monitoringPlotEdits.map { plotEdit ->
          val permanentIndex = plotEdit.permanentIndex
          val plotId = plotEdit.monitoringPlotId
          when (plotEdit) {
            is MonitoringPlotEdit.Create ->
                "$prefix BUG! Create plot $permanentIndex (should be at zone level)"
            is MonitoringPlotEdit.Adopt ->
                if (permanentIndex != null) {
                  "$prefix Adopt plot ID $plotId as permanent index $permanentIndex"
                } else {
                  "$prefix Adopt plot ID $plotId without permanent index"
                }
            is MonitoringPlotEdit.Eject -> "$prefix Eject plot ID $plotId"
          }
        }

    return listOf(subzoneEditText) + monitoringPlotEdits
  }

  private fun renderOverlapPercent(existing: Geometry, desired: Geometry): String {
    return "%.2f".format(existing.intersection(desired).area / desired.area * 100.0)
  }

  private fun redirectToAdminHome() = "redirect:/admin/"

  private fun redirectToOrganization(organizationId: OrganizationId) =
      "redirect:/admin/organization/$organizationId"

  private fun redirectToPlantingSite(plantingSiteId: PlantingSiteId) =
      "redirect:/admin/plantingSite/$plantingSiteId"
}
