package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.tables.daos.FilesDao
import com.terraformation.backend.db.default_schema.tables.daos.SplatsDao
import com.terraformation.backend.db.tracking.ObservationId
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
      @RequestParam ssimLambda: Double?,
      @RequestParam tailPruning: Boolean?,
      @RequestParam otherArgs: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val args =
          listOfNotNull(
                  abortAfter?.ifBlank { null }?.let { listOf("--abort-after", abortAfter) },
                  dataFactor?.let { listOf("--data-factor", "$dataFactor") },
                  fps?.let { listOf("--fps", "$fps") },
                  keepPercent?.let {
                    if (keepPercent < 100.0) {
                      listOf("--keep-percent", "${keepPercent / 100.0}")
                    } else {
                      listOf("--no-filter-blurry")
                    }
                  },
                  mapper?.ifBlank { null }?.let { listOf("--mapper", mapper) },
                  maxSize?.let { listOf("--max-size", "$maxSize") },
                  maxSteps?.let { listOf("--max-steps", "$maxSteps") },
                  restartAt?.ifBlank { null }?.let { listOf("--restart-at", restartAt) },
                  ssimLambda?.let { listOf("--ssim-lambda", "$ssimLambda") },
                  tailPruning?.let {
                    if (tailPruning) {
                      listOf("--prune-tail")
                    } else {
                      listOf("--no-prune-tail")
                    }
                  },
                  otherArgs?.ifEmpty { null }?.split(" "),
              )
              .flatten()
              .ifEmpty { null }

      splatService.generateObservationSplat(observationId, fileId, true, args)

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
