package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
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
    private val splatService: SplatService,
) {
  @GetMapping("/splats")
  fun splatsHome(): String {
    return "/admin/splats"
  }

  @PostMapping("/splats/process")
  fun processSplat(
      @RequestParam observationId: ObservationId,
      @RequestParam fileId: FileId,
      @RequestParam dataFactor: Int?,
      @RequestParam fps: Int?,
      @RequestParam keepPercent: Double?,
      @RequestParam maxSize: Int?,
      @RequestParam maxSteps: Int?,
      @RequestParam ssimLambda: Double?,
      @RequestParam otherArgs: String?,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val args =
          listOfNotNull(
                  dataFactor?.let { listOf("--data-factor", "$dataFactor") },
                  fps?.let { listOf("--fps", "$fps") },
                  keepPercent?.let { listOf("--keep-percent", "${keepPercent / 100.0}") },
                  maxSize?.let { listOf("--max-size", "$maxSize") },
                  maxSteps?.let { listOf("--max-steps", "$maxSteps") },
                  ssimLambda?.let { listOf("--ssim-lambda", "$ssimLambda") },
                  otherArgs?.ifEmpty { null }?.split(" "),
              )
              .flatten()
              .ifEmpty { null }

      splatService.generateObservationSplat(observationId, fileId, true, args)
      redirectAttributes.successMessage = "Sent request to splatter service."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Splat generation failed: ${e.message}"
    }

    return redirectToSplatsHome()
  }

  private fun redirectToSplatsHome() = "redirect:/admin/splats"
}
