package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SIMPLIFIED_PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.SIMPLIFIED_PLANTING_SITE_HISTORIES
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.db.PlantingSiteStore
import java.math.BigDecimal
import org.jooq.Condition
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
    private val organizationsDao: OrganizationsDao,
    private val plantingSiteStore: PlantingSiteStore,
    private val systemUser: SystemUser,
) {
  private val log = perClassLogger()

  @GetMapping("/simplifyPlantingSites")
  fun getManagePlantingSitesSimplification(model: Model): String {
    val organizations = organizationsDao.findAll().sortedBy { it.id }
    val siteStatsByOrg = fetchOrganizationSiteStats()
    val historyStatsByOrg = fetchOrganizationHistoryStats()

    val rows = organizations.map { org ->
      val siteStats = siteStatsByOrg[org.id!!] ?: SimplificationStats.EMPTY
      val historyStats = historyStatsByOrg[org.id!!] ?: SimplificationStats.EMPTY
      OrganizationSimplificationStats(
          organizationId = org.id!!,
          organizationName = org.name!!,
          numPlantingSites = siteStats.total,
          numSimplifiedPlantingSites = siteStats.numSimplified,
          plantingSiteReductionRatio =
              reductionRatio(siteStats.totalOriginalPts, siteStats.totalSimplifiedPts),
          numHistories = historyStats.total,
          numSimplifiedHistories = historyStats.numSimplified,
          historyReductionRatio =
              reductionRatio(historyStats.totalOriginalPts, historyStats.totalSimplifiedPts),
      )
    }

    val totalSitesOriginalPts = siteStatsByOrg.values.sumOf { it.totalOriginalPts }
    val totalSitesSimplifiedPts = siteStatsByOrg.values.sumOf { it.totalSimplifiedPts }
    val totalHistoriesOriginalPts = historyStatsByOrg.values.sumOf { it.totalOriginalPts }
    val totalHistoriesSimplifiedPts = historyStatsByOrg.values.sumOf { it.totalSimplifiedPts }
    val totals =
        SimplificationTotals(
            numPlantingSites = rows.sumOf { it.numPlantingSites },
            numSimplifiedPlantingSites = rows.sumOf { it.numSimplifiedPlantingSites },
            plantingSiteReductionRatio =
                reductionRatio(totalSitesOriginalPts, totalSitesSimplifiedPts),
            numHistories = rows.sumOf { it.numHistories },
            numSimplifiedHistories = rows.sumOf { it.numSimplifiedHistories },
            historyReductionRatio =
                reductionRatio(totalHistoriesOriginalPts, totalHistoriesSimplifiedPts),
        )

    model.addAttribute("organizations", rows)
    model.addAttribute("totals", totals)

    return "/admin/managePlantingSitesSimplification"
  }

  @GetMapping("/simplifyPlantingSites/{organizationId}")
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
            .and(PLANTING_SITES.BOUNDARY.isNotNull)
            .orderBy(PLANTING_SITES.ID)
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

  @GetMapping("/simplifyPlantingSites/{organizationId}/{plantingSiteId}/histories")
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

  @PostMapping("/simplifyPlantingSites")
  fun simplifyPlantingSites(
      @RequestParam(required = false) organizationId: OrganizationId?,
      @RequestParam(required = false) plantingSiteId: PlantingSiteId?,
      @RequestParam(required = false) tolerance: Double?,
      @RequestParam(required = false) unsimplifiedOnly: Boolean = false,
      redirectAttributes: RedirectAttributes,
  ): String {
    val plantingSiteIds =
        if (plantingSiteId != null) {
          listOf(plantingSiteId)
        } else {
          dslContext
              .select(PLANTING_SITES.ID)
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.BOUNDARY.isNotNull)
              .and(organizationId?.let(PLANTING_SITES.ORGANIZATION_ID::eq) ?: DSL.noCondition())
              .and(unsimplifiedSiteCondition(unsimplifiedOnly))
              .orderBy(PLANTING_SITES.ID)
              .fetch(PLANTING_SITES.ID.asNonNullable())
        }

    val failures = mutableMapOf<PlantingSiteId, String?>()

    systemUser.run {
      plantingSiteIds.forEach { id ->
        try {
          plantingSiteStore.upsertSimplifiedPlantingSite(id, tolerance)
        } catch (e: Exception) {
          log.warn("Failed to simplify planting site $id", e)
          failures[id] = e.message
        }
      }
    }

    val successCount = plantingSiteIds.size - failures.size
    if (plantingSiteId != null) {
      if (failures.isEmpty()) {
        redirectAttributes.successMessage = "Simplified planting site $plantingSiteId."
      } else {
        redirectAttributes.failureMessage = "Failed to simplify planting site:"
      }
    } else {
      if (failures.isEmpty()) {
        redirectAttributes.successMessage = "Simplified $successCount planting site(s)."
      } else {
        redirectAttributes.failureMessage =
            "Simplified $successCount of ${plantingSiteIds.size} planting site(s). Failed:"
      }
    }

    if (failures.isNotEmpty()) {
      redirectAttributes.failureDetails = failures.map { (id, message) -> "$id: $message" }
    }

    return if (organizationId != null) {
      redirectToSimplifyPlantingSites(organizationId)
    } else {
      redirectToManagePlantingSitesSimplification()
    }
  }

  @PostMapping("/simplifyPlantingSites/histories")
  fun simplifyPlantingSiteHistories(
      @RequestParam(required = false) organizationId: OrganizationId?,
      @RequestParam(required = false) plantingSiteId: PlantingSiteId?,
      @RequestParam(required = false) historyId: PlantingSiteHistoryId?,
      @RequestParam(required = false) tolerance: Double?,
      @RequestParam(required = false) unsimplifiedOnly: Boolean = false,
      redirectAttributes: RedirectAttributes,
  ): String {
    val historyPairs: List<Pair<PlantingSiteId, PlantingSiteHistoryId>> =
        if (historyId != null) {
          requireNotNull(plantingSiteId) { "plantingSiteId is required when historyId is provided" }
          listOf(plantingSiteId to historyId)
        } else {
          dslContext
              .select(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID, PLANTING_SITE_HISTORIES.ID)
              .from(PLANTING_SITE_HISTORIES)
              .join(PLANTING_SITES)
              .on(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
              .where(
                  plantingSiteId?.let(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID::eq)
                      ?: DSL.noCondition()
              )
              .and(organizationId?.let(PLANTING_SITES.ORGANIZATION_ID::eq) ?: DSL.noCondition())
              .and(unsimplifiedHistoryCondition(unsimplifiedOnly))
              .orderBy(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID, PLANTING_SITE_HISTORIES.ID.desc())
              .fetch { record ->
                record[PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.asNonNullable()] to
                    record[PLANTING_SITE_HISTORIES.ID.asNonNullable()]
              }
        }

    val failures = mutableMapOf<PlantingSiteHistoryId, String?>()

    systemUser.run {
      historyPairs.forEach { (siteId, history) ->
        try {
          plantingSiteStore.upsertSimplifiedPlantingSiteHistory(siteId, history, tolerance)
        } catch (e: Exception) {
          log.warn("Failed to simplify planting site history $history", e)
          failures[history] = e.message
        }
      }
    }

    val successCount = historyPairs.size - failures.size
    when {
      historyId != null -> {
        if (failures.isEmpty()) {
          redirectAttributes.successMessage = "Simplified planting site history $historyId."
        } else {
          redirectAttributes.failureMessage = "Failed to simplify planting site history:"
        }
      }
      plantingSiteId != null -> {
        if (failures.isEmpty()) {
          redirectAttributes.successMessage =
              "Simplified $successCount planting site history(ies) for site $plantingSiteId."
        } else {
          redirectAttributes.failureMessage =
              "Simplified $successCount of ${historyPairs.size} planting site history(ies). Failed:"
        }
      }
      else -> {
        if (failures.isEmpty()) {
          redirectAttributes.successMessage = "Simplified $successCount planting site history(ies)."
        } else {
          redirectAttributes.failureMessage =
              "Simplified $successCount of ${historyPairs.size} planting site history(ies). Failed:"
        }
      }
    }

    if (failures.isNotEmpty()) {
      redirectAttributes.failureDetails = failures.map { (id, message) -> "$id: $message" }
    }

    return when {
      organizationId != null && plantingSiteId != null ->
          redirectToSimplifyPlantingSiteHistories(organizationId, plantingSiteId)
      organizationId != null -> redirectToSimplifyPlantingSites(organizationId)
      else -> redirectToManagePlantingSitesSimplification()
    }
  }

  private fun fetchOrganizationSiteStats(): Map<OrganizationId, SimplificationStats> {
    val total = DSL.count()
    val numSimplified = DSL.count(SIMPLIFIED_PLANTING_SITES.PLANTING_SITE_ID)
    val totalOriginalPts =
        DSL.coalesce(
            DSL.sum(
                DSL.`when`(
                        SIMPLIFIED_PLANTING_SITES.PLANTING_SITE_ID.isNotNull,
                        numVerticesField(PLANTING_SITES.BOUNDARY),
                    )
                    .otherwise(0)
            ),
            BigDecimal.ZERO,
        )
    val totalSimplifiedPts =
        DSL.coalesce(
            DSL.sum(numVerticesField(SIMPLIFIED_PLANTING_SITES.BOUNDARY)),
            BigDecimal.ZERO,
        )

    return dslContext
        .select(
            PLANTING_SITES.ORGANIZATION_ID,
            total,
            numSimplified,
            totalOriginalPts,
            totalSimplifiedPts,
        )
        .from(PLANTING_SITES)
        .leftJoin(SIMPLIFIED_PLANTING_SITES)
        .on(PLANTING_SITES.ID.eq(SIMPLIFIED_PLANTING_SITES.PLANTING_SITE_ID))
        .where(PLANTING_SITES.BOUNDARY.isNotNull)
        .groupBy(PLANTING_SITES.ORGANIZATION_ID)
        .fetch { record ->
          record[PLANTING_SITES.ORGANIZATION_ID.asNonNullable()] to
              SimplificationStats(
                  total = record[total],
                  numSimplified = record[numSimplified],
                  totalOriginalPts = record[totalOriginalPts] ?: BigDecimal.ZERO,
                  totalSimplifiedPts = record[totalSimplifiedPts] ?: BigDecimal.ZERO,
              )
        }
        .toMap()
  }

  private fun fetchOrganizationHistoryStats(): Map<OrganizationId, SimplificationStats> {
    val total = DSL.count()
    val numSimplified = DSL.count(SIMPLIFIED_PLANTING_SITE_HISTORIES.PLANTING_SITE_HISTORY_ID)
    val totalOriginalPts =
        DSL.coalesce(
            DSL.sum(
                DSL.`when`(
                        SIMPLIFIED_PLANTING_SITE_HISTORIES.PLANTING_SITE_HISTORY_ID.isNotNull,
                        numVerticesField(PLANTING_SITE_HISTORIES.BOUNDARY),
                    )
                    .otherwise(0)
            ),
            BigDecimal.ZERO,
        )
    val totalSimplifiedPts =
        DSL.coalesce(
            DSL.sum(numVerticesField(SIMPLIFIED_PLANTING_SITE_HISTORIES.BOUNDARY)),
            BigDecimal.ZERO,
        )

    return dslContext
        .select(
            PLANTING_SITES.ORGANIZATION_ID,
            total,
            numSimplified,
            totalOriginalPts,
            totalSimplifiedPts,
        )
        .from(PLANTING_SITE_HISTORIES)
        .join(PLANTING_SITES)
        .on(PLANTING_SITE_HISTORIES.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        .leftJoin(SIMPLIFIED_PLANTING_SITE_HISTORIES)
        .on(
            PLANTING_SITE_HISTORIES.ID.eq(
                SIMPLIFIED_PLANTING_SITE_HISTORIES.PLANTING_SITE_HISTORY_ID
            )
        )
        .groupBy(PLANTING_SITES.ORGANIZATION_ID)
        .fetch { record ->
          record[PLANTING_SITES.ORGANIZATION_ID.asNonNullable()] to
              SimplificationStats(
                  total = record[total],
                  numSimplified = record[numSimplified],
                  totalOriginalPts = record[totalOriginalPts] ?: BigDecimal.ZERO,
                  totalSimplifiedPts = record[totalSimplifiedPts] ?: BigDecimal.ZERO,
              )
        }
        .toMap()
  }

  private fun reductionRatio(originalPts: BigDecimal, simplifiedPts: BigDecimal): Double? =
      if (originalPts.signum() > 0) 1.0 - simplifiedPts.toDouble() / originalPts.toDouble()
      else null

  private fun unsimplifiedSiteCondition(unsimplifiedOnly: Boolean): Condition =
      if (unsimplifiedOnly) {
        DSL.notExists(
            DSL.selectOne()
                .from(SIMPLIFIED_PLANTING_SITES)
                .where(SIMPLIFIED_PLANTING_SITES.PLANTING_SITE_ID.eq(PLANTING_SITES.ID))
        )
      } else {
        DSL.noCondition()
      }

  private fun unsimplifiedHistoryCondition(unsimplifiedOnly: Boolean): Condition =
      if (unsimplifiedOnly) {
        DSL.notExists(
            DSL.selectOne()
                .from(SIMPLIFIED_PLANTING_SITE_HISTORIES)
                .where(
                    SIMPLIFIED_PLANTING_SITE_HISTORIES.PLANTING_SITE_HISTORY_ID.eq(
                        PLANTING_SITE_HISTORIES.ID
                    )
                )
        )
      } else {
        DSL.noCondition()
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

  private fun redirectToManagePlantingSitesSimplification() =
      "redirect:/admin/simplifyPlantingSites"

  private fun redirectToSimplifyPlantingSites(organizationId: OrganizationId) =
      "redirect:/admin/simplifyPlantingSites/$organizationId"

  private fun redirectToSimplifyPlantingSiteHistories(
      organizationId: OrganizationId,
      plantingSiteId: PlantingSiteId,
  ) = "redirect:/admin/simplifyPlantingSites/$organizationId/$plantingSiteId/histories"
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

data class OrganizationSimplificationStats(
    val organizationId: OrganizationId,
    val organizationName: String,
    val numPlantingSites: Int,
    val numSimplifiedPlantingSites: Int,
    val plantingSiteReductionRatio: Double?,
    val numHistories: Int,
    val numSimplifiedHistories: Int,
    val historyReductionRatio: Double?,
)

data class SimplificationTotals(
    val numPlantingSites: Int,
    val numSimplifiedPlantingSites: Int,
    val plantingSiteReductionRatio: Double?,
    val numHistories: Int,
    val numSimplifiedHistories: Int,
    val historyReductionRatio: Double?,
)

private data class SimplificationStats(
    val total: Int,
    val numSimplified: Int,
    val totalOriginalPts: BigDecimal,
    val totalSimplifiedPts: BigDecimal,
) {
  companion object {
    val EMPTY = SimplificationStats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO)
  }
}
