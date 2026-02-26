package com.terraformation.backend.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOTS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.util.Turtle
import org.jooq.DSLContext
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.WKTReader
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
class AdminMonitoringPlotsController(
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
) {
  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

  @GetMapping("/monitoringPlots")
  fun monitoringPlotsHome(redirectAttributes: RedirectAttributes, model: Model): String {
    return "/admin/monitoringPlots"
  }

  @PostMapping("/calculatePlotBoundary")
  fun calculatePlotBoundary(
      @RequestParam monitoringPlotId: MonitoringPlotId?,
      @RequestParam("coordinates") coordinatesStr: String?,
      @RequestParam("gridOrigin") gridOriginStr: String?,
      redirectAttributes: RedirectAttributes,
      model: Model,
  ): String {
    try {
      val targetCoordinates: Coordinate
      val gridOrigin: Point
      if (monitoringPlotId != null) {
        val existingPlot =
            dslContext.fetchSingle(MONITORING_PLOTS, MONITORING_PLOTS.ID.eq(monitoringPlotId))
        val plantingSite =
            dslContext.fetchSingle(
                PLANTING_SITES,
                PLANTING_SITES.ID.eq(existingPlot.plantingSiteId),
            )
        targetCoordinates = existingPlot.boundary!!.coordinates[0]!!
        gridOrigin = plantingSite.gridOrigin!!.centroid
      } else if (coordinatesStr != null && gridOriginStr != null) {
        gridOrigin =
            if (gridOriginStr.startsWith("{")) objectMapper.readValue<Point>(gridOriginStr)
            else WKTReader(geometryFactory).read(gridOriginStr).centroid
        targetCoordinates =
            if (coordinatesStr.startsWith("{"))
                objectMapper.readValue<Geometry>(coordinatesStr).coordinates[0]
            else WKTReader(geometryFactory).read(coordinatesStr).coordinates[0]
      } else {
        throw IllegalArgumentException(
            "Monitoring plot ID or grid origin + target coordinates must be specified"
        )
      }

      val turtle = Turtle(gridOrigin)

      // Find a point that's at or to the northeast of the target coordinates. Add a little fuzz
      // factor to account for floating-point drift and limited-precision coordinates in GeoJSON.
      while (turtle.currentPosition.getOrdinate(1) + 0.000001 < targetCoordinates.y) {
        turtle.north(30)
      }
      while (turtle.currentPosition.getOrdinate(0) + 0.000001 < targetCoordinates.x) {
        turtle.east(30)
      }

      val newBoundary = turtle.makePolygon { square(30) }

      redirectAttributes.successMessage = "Found matching polygon"
      redirectAttributes.successDetails =
          listOf(
              "Target coordinates: $targetCoordinates",
              "New boundary (WKT): $newBoundary",
              "New boundary (GeoJSON): ${objectMapper.writeValueAsString(newBoundary)}",
          )
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Cannot calculate coordinates: $e"
    }

    return redirectToMonitoringPlots()
  }

  private fun redirectToMonitoringPlots() = "redirect:/admin/monitoringPlots"
}
