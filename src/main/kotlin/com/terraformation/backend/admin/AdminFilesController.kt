package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.file.FileService
import com.terraformation.backend.log.perClassLogger
import java.time.Duration
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
class AdminFilesController(
    private val config: TerrawareServerConfig,
    private val fileService: FileService,
) {
  private val log = perClassLogger()

  @GetMapping("/fileAccessTokens")
  fun getFileAccessTokens(model: Model): String {
    return "/admin/fileAccessTokens"
  }

  @PostMapping("/createFileAccessToken")
  fun createFileAccessToken(
      @RequestParam fileId: FileId,
      @RequestParam expiration: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val duration = Duration.parse("PT$expiration")
      val token = fileService.createToken(fileId, duration)
      val fetchUrl = config.webAppUrl.resolve("/api/v1/files/tokens/$token")

      redirectAttributes.successMessage = "Created token $token"
      redirectAttributes.successDetails = listOf("URL to fetch: $fetchUrl")
    } catch (e: Exception) {
      log.error("Error creating token", e)
      redirectAttributes.failureMessage = "Failed to create token: ${e.message}"
    }

    return redirectToFileAccessTokens()
  }

  private fun redirectToFileAccessTokens() = "redirect:/admin/fileAccessTokens"
}
