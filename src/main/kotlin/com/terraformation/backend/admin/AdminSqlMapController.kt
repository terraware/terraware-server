package com.terraformation.backend.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.mapbox.MapboxService
import org.jooq.exception.DataAccessException
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin/sqlMap")
@RequireGlobalRole([GlobalRole.SuperAdmin])
class AdminSqlMapController(
    private val config: TerrawareServerConfig,
    private val mapboxService: MapboxService,
    private val objectMapper: ObjectMapper,
    private val sqlMapQueryService: SqlMapQueryService,
) {
  private val log = perClassLogger()

  @GetMapping
  fun getSqlMap(): String {
    return "/admin/sqlMap"
  }

  @PostMapping
  fun runSqlMap(@RequestParam query: String, redirectAttributes: RedirectAttributes): String {
    redirectAttributes.addFlashAttribute("query", query)

    try {
      val result = sqlMapQueryService.runQuery(query)

      redirectAttributes.addFlashAttribute(
          "featureCollection",
          objectMapper.valueToTree(result.featureCollection),
      )
      redirectAttributes.addFlashAttribute("mapboxToken", mapboxService.generateTemporaryToken())
      redirectAttributes.addFlashAttribute("rowCount", result.rowCount)
      redirectAttributes.addFlashAttribute(
          "skippedNullGeometryCount",
          result.skippedNullGeometryCount,
      )
      redirectAttributes.addFlashAttribute("rowLimitReached", result.rowLimitReached)
    } catch (e: DataAccessException) {
      log.info("SQL map query failed", e)
      redirectAttributes.addFlashAttribute("errorMessage", "Query failed: ${e.cause?.message}")
    } catch (e: Exception) {
      log.info("SQL map query failed", e)
      redirectAttributes.addFlashAttribute("errorMessage", "Query failed: ${e.message}")
    }

    return "redirect:/admin/sqlMap"
  }
}
