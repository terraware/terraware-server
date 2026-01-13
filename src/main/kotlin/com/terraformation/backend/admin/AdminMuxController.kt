package com.terraformation.backend.admin

import com.terraformation.backend.api.CacheControlBehavior
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.api.toResponseEntity
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.ThumbnailService
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.file.mux.MuxService
import com.terraformation.backend.log.perClassLogger
import java.time.Duration
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminMuxController(
    private val config: TerrawareServerConfig,
    private val fileService: FileService,
    private val muxService: MuxService,
    private val thumbnailService: ThumbnailService,
) {
  private val log = perClassLogger()

  @GetMapping("/mux")
  fun muxAdminHome(
      @RequestParam fileId: FileId?,
      model: Model,
  ): String {
    model.addAttribute("muxEnabled", config.mux.enabled)
    model.addAttribute("fileId", fileId)

    if (fileId != null) {
      try {
        model.addAttribute("muxStream", muxService.getMuxStream(fileId))
      } catch (e: Exception) {
        log.error("Can't get Mux stream for file $fileId", e)
        model.addAttribute("failureMessage", "Can't get Mux stream for file $fileId: ${e.message}")
      }
    }

    return "/admin/mux"
  }

  @GetMapping("/mux/thumbnail/{fileId}")
  @ResponseBody
  fun getMuxThumbnail(
      @PathVariable fileId: FileId,
      @RequestParam width: Int?,
      @RequestParam height: Int?,
  ): ResponseEntity<InputStreamResource> {
    return try {
      thumbnailService
          .readFile(fileId, width, height)
          .toResponseEntity(cacheControlBehavior = CacheControlBehavior.IMMUTABLE)
    } catch (e: Exception) {
      log.error("Unable to get thumbnail", e)
      throw e
    }
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

    return redirectToMuxHome()
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
      return redirectToMuxHome()
    }
  }

  @PostMapping("/sendVideoToMux")
  fun sendVideoToMux(
      @RequestParam fileId: FileId,
      redirectAttributes: RedirectAttributes,
  ): String {
    val playbackId: String

    try {
      playbackId = muxService.sendFileToMux(fileId)

      redirectAttributes.successMessage =
          "Created asset for file $fileId with playback ID $playbackId"
    } catch (e: Exception) {
      log.error("Error creating Mux asset", e)
      redirectAttributes.failureMessage = "Failed to create asset: ${e.message}"
    }

    return redirectToMuxHome(fileId)
  }

  private fun redirectToMuxHome(fileId: FileId? = null): String {
    val suffix = fileId?.let { "?fileId=$it" } ?: ""
    return "redirect:/admin/mux$suffix"
  }
}
