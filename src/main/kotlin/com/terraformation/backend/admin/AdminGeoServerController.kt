package com.terraformation.backend.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.gis.geoserver.GeoServerClient
import com.terraformation.backend.tracking.mapbox.MapboxService
import java.io.ByteArrayOutputStream
import org.geotools.geojson.GeoJSON
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin/geoServer")
@RequireGlobalRole([GlobalRole.AcceleratorAdmin, GlobalRole.SuperAdmin])
@Validated
class AdminGeoServerController(
    private val config: TerrawareServerConfig,
    private val geoServerClient: GeoServerClient,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
) {
  @GetMapping
  fun getGeoServerHome(model: Model): String {
    val enabled = config.geoServer.wfsUrl != null
    model.addAttribute("enabled", enabled)
    addAttributeIfAbsent(model, "filter", "")
    addAttributeIfAbsent(model, "cqlProperties", "geom,area_ha,project_id,site,strata,substrata")
    addAttributeIfAbsent(model, "forceLongLat", true)
    addAttributeIfAbsent(model, "showMap", false)

    if (enabled) {
      model.addAttribute(
          "availableProperties",
          geoServerClient
              .describeFeatureType("tf_accelerator:planting_sites")
              .properties
              .map { it.name }
              .sorted()
              .joinToString(", "))
    }

    return "/admin/geoServer"
  }

  @PostMapping("/cql")
  fun postCqlQuery(
      @RequestParam filter: String,
      @RequestParam properties: String? = null,
      @RequestParam forceLongLat: Boolean = false,
      @RequestParam showMap: Boolean = false,
      redirectAttributes: RedirectAttributes,
  ): String {
    // We always need the "fid" property if we're showing the map, since it's used by the
    // highlighting logic.
    val requestedProperties =
        properties?.ifBlank { null }?.split(",")?.map { it.trim() } ?: emptyList()
    val propertiesWithFid =
        if (showMap) (requestedProperties + "fid").distinct() else requestedProperties

    val features =
        geoServerClient.getPlantingSiteFeatures(
            filter,
            propertiesWithFid,
            forceLongLat,
        )

    if (!features.isEmpty) {
      val outputStream = ByteArrayOutputStream()
      GeoJSON.write(features, outputStream)
      val jsonTree = objectMapper.readTree(outputStream.toByteArray())
      val prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonTree)

      if (showMap) {
        redirectAttributes.addFlashAttribute("geoJsonResults", jsonTree)
      } else {
        redirectAttributes.addFlashAttribute("results", prettyJson)
      }
    } else {
      redirectAttributes.failureMessage = "Query returned no results."
    }

    redirectAttributes.addFlashAttribute("filter", filter)
    redirectAttributes.addFlashAttribute("forceLongLat", forceLongLat || showMap)
    redirectAttributes.addFlashAttribute("cqlProperties", properties)
    redirectAttributes.addFlashAttribute("showMap", showMap)
    redirectAttributes.addFlashAttribute("mapboxToken", mapboxService.generateTemporaryToken())

    return redirectToGeoServerHome()
  }

  private fun addAttributeIfAbsent(model: Model, name: String, value: Any) {
    if (!model.containsAttribute(name)) {
      model.addAttribute(name, value)
    }
  }

  private fun redirectToGeoServerHome() = "redirect:/admin/geoServer"
}
