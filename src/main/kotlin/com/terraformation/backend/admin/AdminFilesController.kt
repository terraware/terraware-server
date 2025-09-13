package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.MuxService
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import java.time.Duration
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminFilesController(
    private val config: TerrawareServerConfig,
    private val fileService: FileService,
    private val muxService: MuxService,
) {
  private val log = perClassLogger()

  @GetMapping("/fileAccessTokens")
  fun getFileAccessTokens(@RequestParam muxPlaybackId: String?, model: Model): String {
    if (muxPlaybackId != null) {
      model.addAttribute("muxPlaybackId", muxPlaybackId)
      model.addAttribute(
          "muxPlaybackToken",
          muxService.generatePlaybackToken(muxPlaybackId, Duration.ofHours(1)),
      )
    }

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

  @PostMapping("/uploadMuxVideo")
  fun uploadMuxVideo(
      @RequestPart file: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val fileId =
          fileService.storeFile(
              "muxAsset",
              file.inputStream,
              NewFileMetadata.of(
                  file.getPlainContentType() ?: MediaType.APPLICATION_OCTET_STREAM_VALUE,
                  file.name,
                  file.size,
              ),
          ) {}

      return sendVideoToMux(fileId, redirectAttributes)
    } catch (e: Exception) {
      log.error("Error storing file", e)
      redirectAttributes.failureMessage = "Failed to store file: ${e.message}"
      return redirectToFileAccessTokens()
    }
  }

  @PostMapping("/sendVideoToMux")
  fun sendVideoToMux(
      @RequestParam fileId: FileId,
      redirectAttributes: RedirectAttributes,
  ): String {
    var playbackId: String? = null

    try {
      playbackId = muxService.processFile(fileId)

      redirectAttributes.successMessage =
          "Created asset for file $fileId with playback ID $playbackId"
    } catch (e: Exception) {
      log.error("Error creating Mux asset", e)
      redirectAttributes.failureMessage = "Failed to create asset: ${e.message}"
    }

    return redirectToFileAccessTokens(playbackId)
  }

  private fun redirectToFileAccessTokens(muxPlaybackId: String? = null): String {
    val suffix = muxPlaybackId?.let { "?muxPlaybackId=$it" } ?: ""
    return "redirect:/admin/fileAccessTokens$suffix"
  }
}
