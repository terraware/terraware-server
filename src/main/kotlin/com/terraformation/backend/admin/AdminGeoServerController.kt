package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.ProjectAcceleratorDetailsService
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.gis.geoserver.GeoServerClient
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.mapbox.MapboxService
import java.io.ByteArrayOutputStream
import org.geotools.geojson.feature.FeatureJSON
import org.geotools.geojson.geom.GeometryJSON
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import tools.jackson.databind.ObjectMapper

@Controller
@RequestMapping("/admin/geoServer")
@RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
@Validated
class AdminGeoServerController(
    private val config: TerrawareServerConfig,
    private val geoServerClient: GeoServerClient,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
    private val projectAcceleratorDetailsService: ProjectAcceleratorDetailsService,
) {
  private val log = perClassLogger()

  @GetMapping
  fun getGeoServerHome(model: Model): String {
    val enabled = config.geoServer.wfsUrl != null
    model.addAttribute("enabled", enabled)
    model.addAttribute("availableFeatureTypes", FeatureType.entries)
    addAttributeIfAbsent(model, "filter", "")
    addAttributeIfAbsent(model, "featureType", FeatureType.PlantingSites)
    addAttributeIfAbsent(model, "resultFormat", CqlQueryResultFormat.Map.toString())

    return "/admin/geoServer"
  }

  @PostMapping("/cql")
  fun postCqlQuery(
      @RequestParam featureType: FeatureType,
      @RequestParam filter: String,
      @RequestParam resultFormat: CqlQueryResultFormat = CqlQueryResultFormat.Map,
      redirectAttributes: RedirectAttributes,
  ): String {
    val features =
        geoServerClient.getFeatures(featureType.typeName, filter, null, resultFormat.srsName)

    if (!features.isEmpty) {
      val decimalPlaces = 6
      val outputStream = ByteArrayOutputStream()
      FeatureJSON(GeometryJSON(decimalPlaces)).writeFeatureCollection(features, outputStream)
      val jsonTree = objectMapper.readTree(outputStream.toByteArray())

      if (resultFormat == CqlQueryResultFormat.Map) {
        redirectAttributes.addFlashAttribute("geoJsonResults", jsonTree)
      } else {
        redirectAttributes.addFlashAttribute(
            "results",
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonTree),
        )
      }
    } else {
      redirectAttributes.failureMessage = "Query returned no results."
    }

    redirectAttributes.addFlashAttribute("featureType", featureType)
    redirectAttributes.addFlashAttribute("filter", filter)
    redirectAttributes.addFlashAttribute("resultFormat", resultFormat.toString())
    redirectAttributes.addFlashAttribute("mapboxToken", mapboxService.generateTemporaryToken())

    return redirectToGeoServerHome()
  }

  @PostMapping("/setForProject")
  fun setCqlForProject(
      @RequestParam projectId: ProjectId,
      @RequestParam featureType: FeatureType,
      @RequestParam filter: String,
      @RequestParam resultFormat: CqlQueryResultFormat,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      projectAcceleratorDetailsService.update(projectId) { details ->
        when (featureType) {
          FeatureType.PlantingSites -> details.copy(plantingSitesCql = filter)
          FeatureType.ProjectBoundaries -> details.copy(projectBoundariesCql = filter)
        }
      }

      redirectAttributes.successMessage = "Updated project $projectId."
    } catch (e: Exception) {
      log.error("Unable to set CQL for project", e)
      redirectAttributes.failureMessage = "Failed to set CQL for project: ${e.message}"
    }

    return postCqlQuery(featureType, filter, resultFormat, redirectAttributes)
  }

  private fun addAttributeIfAbsent(model: Model, name: String, value: Any) {
    if (!model.containsAttribute(name)) {
      model.addAttribute(name, value)
    }
  }

  private fun redirectToGeoServerHome() = "redirect:/admin/geoServer"

  enum class CqlQueryResultFormat(val srsName: String? = null) {
    GeoJsonLongLat("EPSG:${SRID.LONG_LAT}"),
    GeoJsonOriginal,
    Map("EPSG:${SRID.LONG_LAT}"),
  }

  enum class FeatureType(val displayName: String, val typeName: String) {
    PlantingSites("Planting Sites", "tf_accelerator:planting_sites"),
    ProjectBoundaries("Project Boundaries", "tf_accelerator:project_boundaries"),
  }
}
