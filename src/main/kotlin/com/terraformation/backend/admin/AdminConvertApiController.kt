package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.api.getPlainContentType
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.file.FileService
import com.terraformation.backend.file.SUPPORTED_PHOTO_TYPES
import com.terraformation.backend.file.SizedInputStream
import com.terraformation.backend.file.convertapi.ConvertApiService
import com.terraformation.backend.file.model.NewFileMetadata
import com.terraformation.backend.log.perClassLogger
import javax.imageio.ImageIO
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
class AdminConvertApiController(
    private val config: TerrawareServerConfig,
    private val convertApiService: ConvertApiService,
    private val fileService: FileService,
) {
  private val log = perClassLogger()

  @GetMapping("/convertApi")
  fun convertApiHome(@RequestParam fileId: FileId?, model: Model): String {
    model.addAttribute("convertApiEnabled", config.convertApi.enabled)
    model.addAttribute("fileId", fileId)
    model.addAttribute("imageFormats", ImageIO.getReaderMIMETypes().sorted())

    return "/admin/convertApi"
  }

  @PostMapping("/convertImage")
  fun convertApiConvertFile(@RequestPart file: MultipartFile): ResponseEntity<ByteArrayResource> {
    val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)
    val converted =
        convertApiService.convertFile(
            SizedInputStream(file.inputStream, file.size, MediaType.valueOf(contentType)),
            MediaType.IMAGE_JPEG,
        )

    return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(ByteArrayResource(converted))
  }

  @PostMapping("/uploadFile")
  fun uploadFile(@RequestPart file: MultipartFile, redirectAttributes: RedirectAttributes): String {
    val fileId =
        try {
          val contentType = file.getPlainContentType(SUPPORTED_PHOTO_TYPES)

          fileService.storeFile(
              "convertApiAdmin",
              file.inputStream,
              NewFileMetadata.of(contentType, file.name, file.size),
          ) {
            redirectAttributes.successMessage = "Uploaded file with ID $it"
          }
        } catch (e: Exception) {
          perClassLogger().error("Error storing file", e)
          redirectAttributes.failureMessage = "Unable to upload file: ${e.message}"
          null
        }

    return redirectToConvertApiHome(fileId)
  }

  private fun redirectToConvertApiHome(fileId: FileId? = null): String {
    val suffix = fileId?.let { "?fileId=$it" } ?: ""
    return "redirect:/admin/convertApi$suffix"
  }
}
