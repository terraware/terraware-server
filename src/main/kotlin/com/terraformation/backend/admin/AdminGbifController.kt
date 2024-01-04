package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.db.GbifImporter
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminGbifController(
    private val gbifImporter: GbifImporter,
) {
  private val log = perClassLogger()

  @PostMapping("/importGbif")
  fun importGbif(
      @RequestParam url: URI,
      redirectAttributes: RedirectAttributes,
  ): String {
    val tempFile = kotlin.io.path.createTempFile(suffix = ".zip")

    try {
      log.info("Copying GBIF zipfile to local filesystem: $tempFile")

      url.toURL().openStream().use { zipFileStream ->
        Files.copy(zipFileStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
      }

      ZipFile(tempFile.toFile()).use { gbifImporter.import(it) }

      redirectAttributes.successMessage = "GBIF data imported successfully."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    } finally {
      tempFile.deleteIfExists()
    }

    return redirectToAdminHome()
  }

  private fun redirectToAdminHome() = "redirect:/admin/"
}
