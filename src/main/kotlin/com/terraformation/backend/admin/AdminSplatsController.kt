package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SplatsDao
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.splat.SplatGenerationParams
import com.terraformation.backend.splat.SplatService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@ConditionalOnProperty("terraware.splatter.enabled")
@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminSplatsController(
    private val filesDao: FilesDao,
    private val splatService: SplatService,
    private val splatsDao: SplatsDao,
) {
  @GetMapping("/splats")
  fun splatsHome(): String {
    return "/admin/splats"
  }

  @PostMapping("/splats/process")
  fun processSplat(
      @RequestParam observationId: ObservationId,
      @RequestParam fileId: FileId,
      @RequestParam abortAfter: String?,
      @RequestParam dataFactor: Int?,
      @RequestParam fps: Int?,
      @RequestParam keepPercent: Double?,
      @RequestParam mapper: String?,
      @RequestParam maxSize: Int?,
      @RequestParam maxSteps: Int?,
      @RequestParam restartAt: String?,
      @RequestParam runBirdNet: Boolean?,
      @RequestParam ssimLambda: Double?,
      @RequestParam tailPruning: Boolean?,
      payload: AdminProcessSplatRequestPayload,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val stepArgs =
          listOfNotNull(
                  dataFactor?.let { "gsplat" to listOf("--data-factor", "$dataFactor") },
                  fps?.let { "extract" to listOf("--fps", "$fps") },
                  keepPercent?.let {
                    if (keepPercent < 100.0) {
                      "filter-blurry" to listOf("--keep-percent", "${keepPercent / 100.0}")
                    } else {
                      "filter-blurry" to listOf("--no-filter-blurry")
                    }
                  },
                  mapper?.ifBlank { null }?.let { "sfm" to listOf("--mapper", mapper) },
                  maxSize?.let { "extract" to listOf("--max-size", "$maxSize") },
                  maxSteps?.let { "gsplat" to listOf("--max_steps", "$maxSteps") },
                  ssimLambda?.let { "gsplat" to listOf("--ssim_lambda", "$ssimLambda") },
                  tailPruning?.let {
                    if (tailPruning) {
                      "prune-tail" to listOf("--prune-tail")
                    } else {
                      "prune-tail" to listOf("--no-prune-tail")
                    }
                  },
              )
              .groupBy { it.first }
              .mapValues { (_, lists) -> lists.flatMap { it.second } }
              .toMutableMap()

      payload.stepArgs.forEach { (stepName, argsString) ->
        if (!argsString.isNullOrBlank()) {
          val splitArgs = argsString.split(' ')
          stepArgs.compute(stepName) { _, args ->
            if (args == null) splitArgs else args + splitArgs
          }
        }
      }

      val params = SplatGenerationParams(abortAfter, restartAt, stepArgs)

      splatService.generateObservationSplat(observationId, fileId, true, params, runBirdNet ?: false)

      val storageUrl = filesDao.fetchOneById(fileId)?.storageUrl
      val modelUrl = splatsDao.fetchOneByFileId(fileId)?.splatStorageUrl
      val jobDirUrl = "$modelUrl-job.tar.gz"

      redirectAttributes.successMessage =
          "Sent request to splatter service. Model and archive will not be available until " +
              "processing is finished."
      redirectAttributes.successDetails =
          listOf(
              "Video: $storageUrl",
              "Model: $modelUrl",
              "Archive: $jobDirUrl",
          )
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Splat generation failed: ${e.message}"
    }

    return redirectToSplatsHome()
  }

  private fun redirectToSplatsHome() = "redirect:/admin/splats"
}

/** "Payload" that accepts form fields with subscripted names such as `stepArgs[extract]`. */
data class AdminProcessSplatRequestPayload(
    val stepArgs: Map<String, String?>,
)
