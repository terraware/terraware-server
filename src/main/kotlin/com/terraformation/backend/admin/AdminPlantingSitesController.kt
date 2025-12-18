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
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
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
import com.terraformation.backend.tracking.edit.StratumEdit
import com.terraformation.backend.tracking.edit.SubstratumEdit
import com.terraformation.backend.tracking.mapbox.MapboxService
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.NewObservationModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.Shapefile
import com.terraformation.backend.tracking.model.SubstratumFullException
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
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Substratum)
    val country = plantingSite.countryCode?.let { countriesDao.fetchOneByCode(it) }
    val plotCounts = plantingSiteStore.countMonitoringPlots(plantingSiteId)
    val plantCounts = plantingSiteStore.countReportedPlantsInSubstrata(plantingSiteId)
    val organization = organizationStore.fetchOneById(plantingSite.organizationId)
    val reportedPlants = plantingSiteStore.countReportedPlants(plantingSiteId)
    val substrataById =
        plantingSite.strata.flatMap { it.substrata }.sortedBy { it.fullName }.associateBy { it.id }

    val allOrganizations =
        if (currentUser().canMovePlantingSiteToAnyOrg(plantingSiteId)) {
          organizationsDao.findAll().sortedBy { it.id }
        } else {
          null
        }

    val months =
        Month.entries.associateWith { it.getDisplayName(TextStyle.FULL_STANDALONE, Locale.ENGLISH) }

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
    model.addAttribute("numStrata", plantingSite.strata.size)
    model.addAttribute(
        "numSubstrata",
        plantingSite.strata.sumOf { it.substrata.size },
    )
    model.addAttribute("numPlots", plotCounts.values.flatMap { it.values }.sum())
    model.addAttribute("observations", observations)
    model.addAttribute("observationMessages", observationMessages)
    model.addAttribute("organization", organization)
    model.addAttribute("pastPlantingSeasons", pastPlantingSeasons)
    model.addAttribute("plantCounts", plantCounts)
    model.addAttribute("plotCounts", plotCounts)
    model.addAttribute("reportedPlants", reportedPlants)
    model.addAttribute("site", plantingSite)
    model.addAttribute("substrataById", substrataById)

    return "/admin/plantingSite"
  }

  @GetMapping("/plantingSite/{plantingSiteId}/map")
  fun getPlantingSiteMap(@PathVariable plantingSiteId: PlantingSiteId, model: Model): String {
    val plantingSite = plantingSiteStore.fetchSiteById(plantingSiteId, PlantingSiteDepth.Substratum)

    model.addAttribute("site", plantingSite)

    if (plantingSite.boundary != null) {
      model.addAttribute("envelope", objectMapper.valueToTree(plantingSite.boundary.envelope))
      model.addAttribute("exclusionGeoJson", objectMapper.valueToTree(plantingSite.exclusion))
      model.addAttribute("siteGeoJson", objectMapper.valueToTree(plantingSite.boundary))
      model.addAttribute("strataGeoJson", objectMapper.valueToTree(strataToGeoJson(plantingSite)))
      model.addAttribute(
          "substrataGeoJson",
          objectMapper.valueToTree(substrataToGeoJson(plantingSite)),
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

  private fun strataToGeoJson(site: ExistingPlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.strata.map { stratum ->
                mapOf(
                    "type" to "Feature",
                    "properties" to mapOf("name" to stratum.name),
                    "geometry" to stratum.boundary,
                )
              },
      )

  private fun substrataToGeoJson(site: ExistingPlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.strata.flatMap { stratum ->
                stratum.substrata.map { substratum ->
                  mapOf(
                      "type" to "Feature",
                      "properties" to mapOf("name" to substratum.name),
                      "geometry" to substratum.boundary,
                  )
                }
              },
      )

  private fun plotsToGeoJson(site: ExistingPlantingSiteModel) =
      mapOf(
          "type" to "FeatureCollection",
          "features" to
              site.strata.flatMap { stratum ->
                val numPermanent = stratum.numPermanentPlots

                val temporaryPlotsAndIds: List<Pair<Polygon, MonitoringPlotId?>> =
                    try {
                      stratum
                          .chooseTemporaryPlots(
                              site.strata
                                  .flatMap { stratum -> stratum.substrata.map { it.id } }
                                  .toSet(),
                              gridOrigin = site.gridOrigin!!,
                              exclusion = site.exclusion,
                          )
                          .map { boundary -> boundary to stratum.findMonitoringPlot(boundary)?.id }
                    } catch (e: SubstratumFullException) {
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
                                      "substratum" to stratum.findSubstratum(plotBoundary)?.name,
                                      "type" to "temporary",
                                      "stratum" to stratum.name,
                                  ),
                          )
                        }

                val existingPlots =
                    stratum.substrata.flatMap { substratum ->
                      val permanentPlotIds =
                          substratum.monitoringPlots
                              .filter {
                                it.permanentIndex != null && it.permanentIndex <= numPermanent
                              }
                              .map { it.id }
                              .toSet()

                      substratum.monitoringPlots.map { plot ->
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
                                "substratum" to substratum.name,
                                "stratum" to stratum.name,
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
            val substrataFile =
                Shapefile.fromBoundary(
                    siteBoundary,
                    mapOf(
                        PlantingSiteImporter.stratumNameProperties.first() to "Stratum",
                        PlantingSiteImporter.substratumNameProperties.first() to "Substratum",
                    ),
                )

            plantingSiteImporter.import(siteName, null, organizationId, listOf(substrataFile))
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
      @RequestParam substratumIdsToMarkIncomplete: String?,
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
              substratumIdsToMarkIncomplete?.split(",")?.map { SubstratumId(it.trim()) }?.toSet()
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

  @PostMapping("/updateStratum")
  fun updateStratum(
      @RequestParam plantingSiteId: PlantingSiteId,
      @RequestParam stratumId: StratumId,
      @RequestParam name: String,
      @RequestParam variance: BigDecimal,
      @RequestParam errorMargin: BigDecimal,
      @RequestParam studentsT: BigDecimal,
      @RequestParam numPermanent: Int,
      @RequestParam numTemporary: Int,
      @RequestParam targetPlantingDensity: BigDecimal,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.updateStratum(stratumId) { row ->
        row.copy(
            errorMargin = errorMargin,
            name = name,
            numPermanentPlots = numPermanent,
            numTemporaryPlots = numTemporary,
            studentsT = studentsT,
            targetPlantingDensity = targetPlantingDensity,
            variance = variance,
        )
      }

      redirectAttributes.successMessage = "Stratum updated successfully."
    } catch (e: Exception) {
      log.warn("Stratum update failed", e)
      redirectAttributes.failureMessage = "Stratum update failed: ${e.message}"
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
      redirectAttributes: RedirectAttributes,
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
      redirectAttributes: RedirectAttributes,
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
      redirectAttributes: RedirectAttributes,
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
      @RequestParam requestedSubstratumIds: Set<SubstratumId>,
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
                  requestedSubstratumIds = requestedSubstratumIds,
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
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { manageObservation(observationId) }

    try {
      observationStore.recalculateSurvivalMortalityRates(observationId, plantingSiteId)
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
      redirectAttributes: RedirectAttributes,
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
    val stratumChanges =
        edit.stratumEdits.flatMap { stratumEdit -> describeStratumEdit(stratumEdit) }

    val affectedObservationIds =
        observationStore.fetchActiveObservationIds(
            edit.existingModel.id,
            edit.stratumEdits.mapNotNull { it.existingModel?.id },
        )
    val affectedObservationsMessage =
        if (affectedObservationIds.isNotEmpty()) {
          "The following observations will be abandoned because they have incomplete " +
              "plots in edited strata: " +
              affectedObservationIds.joinToString(", ")
        } else {
          null
        }

    return listOfNotNull(
        affectedObservationsMessage,
        "Total change in plantable area: ${edit.areaHaDifference.toPlainString()}ha",
    ) + stratumChanges
  }

  private fun describeStratumEdit(stratumEdit: StratumEdit): List<String> {
    val desiredModel = stratumEdit.desiredModel
    val existingModel = stratumEdit.existingModel
    val existingBoundary = existingModel?.boundary
    val stratumName = existingModel?.name ?: desiredModel!!.name
    val prefix = "Stratum $stratumName:"
    val monitoringPlotCreations =
        stratumEdit.monitoringPlotEdits.map { plotEdit ->
          val existingOrNew =
              if (existingBoundary != null && plotEdit.region.nearlyCoveredBy(existingBoundary)) {
                "existing"
              } else {
                "new"
              }
          "$prefix Create permanent plot index ${plotEdit.permanentIndex} in $existingOrNew area"
        }
    val substratumEdits =
        stratumEdit.substratumEdits.flatMap { describeSubstratumEdit(stratumEdit, it) }

    return listOf(
        if (existingModel != null) {
          if (desiredModel != null) {
            val overlapPercent = renderOverlapPercent(existingModel.boundary, desiredModel.boundary)
            val areaDifference = stratumEdit.areaHaDifference.toPlainString()
            "$prefix Change in plantable area: ${areaDifference}ha, overlap $overlapPercent%"
          } else {
            "$prefix Delete (stable ID ${existingModel.stableId})"
          }
        } else {
          "$prefix Create (stable ID ${desiredModel?.stableId})"
        }
    ) + monitoringPlotCreations + substratumEdits
  }

  private fun describeSubstratumEdit(
      stratumEdit: StratumEdit,
      substratumEdit: SubstratumEdit,
  ): List<String> {
    val stratumName = stratumEdit.existingModel?.name ?: stratumEdit.desiredModel!!.name
    val substratumName = substratumEdit.existingModel?.name ?: substratumEdit.desiredModel!!.name
    val prefix = "Stratum $stratumName substratum $substratumName:"
    val substratumEditText =
        when (substratumEdit) {
          is SubstratumEdit.Create ->
              "$prefix Create (stable ID ${substratumEdit.desiredModel.stableId})"
          is SubstratumEdit.Delete ->
              "$prefix Delete (stable ID ${substratumEdit.existingModel.stableId})"
          is SubstratumEdit.Update -> {
            val overlapPercent =
                renderOverlapPercent(
                    substratumEdit.existingModel.boundary,
                    substratumEdit.desiredModel.boundary,
                )
            val areaDifference = substratumEdit.areaHaDifference.toPlainString()
            "$prefix Change in plantable area: ${areaDifference}ha, overlap $overlapPercent%"
          }
        }
    val monitoringPlotEdits =
        substratumEdit.monitoringPlotEdits.map { plotEdit ->
          val permanentIndex = plotEdit.permanentIndex
          val plotId = plotEdit.monitoringPlotId
          when (plotEdit) {
            is MonitoringPlotEdit.Create ->
                "$prefix BUG! Create plot $permanentIndex (should be at stratum level)"
            is MonitoringPlotEdit.Adopt ->
                if (permanentIndex != null) {
                  "$prefix Adopt plot ID $plotId as permanent index $permanentIndex"
                } else {
                  "$prefix Adopt plot ID $plotId without permanent index"
                }
            is MonitoringPlotEdit.Eject -> "$prefix Eject plot ID $plotId"
          }
        }

    return listOf(substratumEditText) + monitoringPlotEdits
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
