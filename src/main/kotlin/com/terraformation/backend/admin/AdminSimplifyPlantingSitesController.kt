package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SIMPLIFIED_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.SIMPLIFIED_PLANTING_SITE_HISTORIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.db.PlantingSiteStore
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.locationtech.jts.geom.Geometry
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
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminSimplifyPlantingSitesController(
    private val dslContext: DSLContext,
    private val plantingSiteStore: PlantingSiteStore,
) {
  private val log = perClassLogger()

  @GetMapping("/organization/{organizationId}/simplifyPlantingSites")
  fun getSimplifyPlantingSites(
      @PathVariable organizationId: OrganizationId,
      model: Model,
  ): String {
    val numPointsOriginal = numVerticesField(PLANTING_SITES.BOUNDARY)
    val numPointsSimplified = numVerticesField(SIMPLIFIED_PLANTING_SITES.BOUNDARY)
    val reductionRatio =
        reductionRatioField(PLANTING_SITES.BOUNDARY, SIMPLIFIED_PLANTING_SITES.BOUNDARY)
    val jaccardSimilarity =
        jaccardSimilarityField(PLANTING_SITES.BOUNDARY, SIMPLIFIED_PLANTING_SITES.BOUNDARY)

    val plantingSites =
        dslContext
            .select(
                PLANTING_SITES.ID,
                PLANTING_SITES.NAME,
                numPointsOriginal,
                numPointsSimplified,
                reductionRatio,
                jaccardSimilarity,
            )
            .from(PLANTING_SITES)
            .leftJoin(SIMPLIFIED_PLANTING_SITES)
            .on(PLANTING_SITES.ID.eq(SIMPLIFIED_PLANTING_SITES.PLANTING_SITE_ID))
            .where(PLANTING_SITES.ORGANIZATION_ID.eq(organizationId))
            .fetch { record ->
              PlantingSiteSimplificationModel(
                  id = record[PLANTING_SITES.ID]!!,
                  name = record[PLANTING_SITES.NAME]!!,
                  numVerticesOriginal = record[numPointsOriginal],
                  numVerticesSimplified = record[numPointsSimplified],
                  reductionRatio = record[reductionRatio],
                  jaccardSimilarity = record[jaccardSimilarity],
              )
            }

    model.addAttribute("organizationId", organizationId)
    model.addAttribute("plantingSites", plantingSites)

    return "/admin/simplifyPlantingSites"
  }

  @GetMapping("/organization/{organizationId}/simplifyPlantingSites/{plantingSiteId}/histories")
  fun getSimplifyPlantingSiteHistories(
      @PathVariable organizationId: OrganizationId,
      @PathVariable plantingSiteId: PlantingSiteId,
      model: Model,
  ): String {
    val numPointsOriginal = numVerticesField(PLANTING_SITE_HISTORIES.BOUNDARY)
    val numPointsSimplified = numVerticesField(SIMPLIFIED_PLANTING_SITE_HISTORIES.BOUNDARY)
    val reductionRatio =
        reductionRatioField(
            PLANTING_SITE_HISTORIES.BOUNDARY,
            SIMPLIFIED_PLANTING_SITE_HISTORIES.BOUNDARY,
        )
    val jaccardSimilarity =
        jaccardSimilarityField(
            PLANTING_SITE_HISTORIES.BOUNDARY,
            SIMPLIFIED_PLANTING_SITE_HISTORIES.BOUNDARY,
        )

    val histories =
        dslContext
            .select(
                PLANTING_SITE_HISTORIES.ID,
                PLANTING_SITE_HISTORIES.CREATED_TIME,
                numPointsOriginal,
                numPointsSimplified,
                reductionRatio,
                jaccardSimilarity,
            )
            .from(PLANTING_SITE_HISTORIES)
            .leftJoin(SIMPLIFIED_PLANTING_SITE_HISTORIES)
            .on(
                PLANTING_SITE_HISTORIES.ID.eq(
                    SIMPLIFIED_PLANTING_SITE_HISTORIES.PLANTING_SITE_HISTORY_ID
                )
            )
            .where(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(plantingSiteId))
            .orderBy(PLANTING_SITE_HISTORIES.ID.desc())
            .fetch { record ->
              PlantingSiteHistorySimplificationModel(
                  id = record[PLANTING_SITE_HISTORIES.ID]!!,
                  createdTime = record[PLANTING_SITE_HISTORIES.CREATED_TIME]!!,
                  numVerticesOriginal = record[numPointsOriginal],
                  numVerticesSimplified = record[numPointsSimplified],
                  reductionRatio = record[reductionRatio],
                  jaccardSimilarity = record[jaccardSimilarity],
              )
            }

    model.addAttribute("organizationId", organizationId)
    model.addAttribute("plantingSiteId", plantingSiteId)
    model.addAttribute("histories", histories)

    return "/admin/simplifyPlantingSiteHistories"
  }

  @PostMapping("/organization/{organizationId}/simplifyPlantingSite/{plantingSiteId}")
  fun simplifyPlantingSite(
      @PathVariable organizationId: OrganizationId,
      @PathVariable plantingSiteId: PlantingSiteId,
      @RequestParam(required = false) tolerance: Double?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.upsertSimplifiedPlantingSite(plantingSiteId, tolerance)
      redirectAttributes.successMessage = "Simplified planting site $plantingSiteId."
    } catch (e: Exception) {
      log.warn("Failed to simplify planting site $plantingSiteId", e)
      redirectAttributes.failureMessage = "Failed to simplify planting site: ${e.message}"
    }

    return redirectToSimplifyPlantingSites(organizationId)
  }

  @PostMapping(
      "/organization/{organizationId}/simplifyPlantingSites/{plantingSiteId}/histories/{historyId}"
  )
  fun simplifyPlantingSiteHistory(
      @PathVariable organizationId: OrganizationId,
      @PathVariable plantingSiteId: PlantingSiteId,
      @PathVariable historyId: PlantingSiteHistoryId,
      @RequestParam(required = false) tolerance: Double?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      plantingSiteStore.upsertSimplifiedPlantingSiteHistory(plantingSiteId, historyId, tolerance)
      redirectAttributes.successMessage = "Simplified planting site history $historyId."
    } catch (e: Exception) {
      log.warn("Failed to simplify planting site history $historyId", e)
      redirectAttributes.failureMessage = "Failed to simplify planting site history: ${e.message}"
    }

    return redirectToSimplifyPlantingSiteHistories(organizationId, plantingSiteId)
  }

  private fun numVerticesField(boundary: Field<Geometry?>): Field<Int> =
      DSL.field("ST_NPoints({0})", SQLDataType.INTEGER, boundary)

  private fun reductionRatioField(
      original: Field<Geometry?>,
      simplified: Field<Geometry?>,
  ): Field<Double> =
      DSL.field(
          "CASE WHEN ST_NPoints({0}) > 0 AND {1} IS NOT NULL " +
              "THEN 1.0 - ST_NPoints({1})::float / ST_NPoints({0})::float " +
              "ELSE NULL END",
          SQLDataType.DOUBLE,
          original,
          simplified,
      )

  private fun jaccardSimilarityField(
      original: Field<Geometry?>,
      simplified: Field<Geometry?>,
  ): Field<Double> =
      DSL.field(
          "CASE WHEN {1} IS NOT NULL " +
              "THEN ST_Area(ST_Intersection({0}, {1})::geography) / " +
              "NULLIF(ST_Area(ST_Union({0}, {1})::geography), 0) " +
              "ELSE NULL END",
          SQLDataType.DOUBLE,
          original,
          simplified,
      )

  private fun redirectToSimplifyPlantingSites(organizationId: OrganizationId) =
      "redirect:/admin/organization/$organizationId/simplifyPlantingSites"

  private fun redirectToSimplifyPlantingSiteHistories(
      organizationId: OrganizationId,
      plantingSiteId: PlantingSiteId,
  ) = "redirect:/admin/organization/$organizationId/simplifyPlantingSites/$plantingSiteId/histories"
}

data class PlantingSiteSimplificationModel(
    val id: PlantingSiteId,
    val name: String,
    val numVerticesOriginal: Int?,
    val numVerticesSimplified: Int?,
    val reductionRatio: Double?,
    val jaccardSimilarity: Double?,
)

data class PlantingSiteHistorySimplificationModel(
    val id: PlantingSiteHistoryId,
    val createdTime: java.time.Instant,
    val numVerticesOriginal: Int?,
    val numVerticesSimplified: Int?,
    val reductionRatio: Double?,
    val jaccardSimilarity: Double?,
)
