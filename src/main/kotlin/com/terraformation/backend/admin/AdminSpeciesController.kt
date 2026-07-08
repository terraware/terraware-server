package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.gis.BotanicalCountryImporter
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.species.WcvpImporter
import com.terraformation.backend.species.db.GbifImporter
import com.terraformation.backend.species.db.GriisImporter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
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
@RequireGlobalRole(
    [GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert, GlobalRole.ReadOnly]
)
@Validated
class AdminSpeciesController(
    private val botanicalCountryImporter: BotanicalCountryImporter,
    private val gbifImporter: GbifImporter,
    private val griisImporter: GriisImporter,
    private val wcvpImporter: WcvpImporter,
) {
  private val log = perClassLogger()

  @GetMapping("/species")
  fun getSpeciesAdminHome(model: Model): String {
    model.addAttribute("canImportGlobalSpeciesData", currentUser().canImportGlobalSpeciesData())
    model.addAttribute(
        "defaultBotanicalCountriesUrl",
        BotanicalCountryImporter.defaultGeoJsonUrl.toString(),
    )
    model.addAttribute("defaultWcvpSpeciesListUrl", WcvpImporter.defaultZipFileUrl.toString())

    return "/admin/species"
  }

  @PostMapping("/importGbif")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun importGbif(
      @RequestParam url: URI,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      withDownloadedFile(url) { zipFilePath ->
        ZipFile(zipFilePath.toFile()).use { zipFile -> gbifImporter.import(zipFile) }
      }

      redirectAttributes.successMessage = "GBIF data imported successfully."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToSpeciesHome()
  }

  @PostMapping("/importBotanicalCountries")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun importBotanicalCountries(
      @RequestParam url: URI,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      withDownloadedFile(url) { path ->
        path.inputStream().use { inputStream ->
          botanicalCountryImporter.importBotanicalCountries(inputStream)
        }
      }

      redirectAttributes.successMessage = "Botanical countries imported successfully."
    } catch (e: Exception) {
      log.error("Botanical country import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToSpeciesHome()
  }

  @PostMapping("/importGriisResources")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun importGriisResources(
      @RequestParam resourceName: String? = null,
      @RequestParam forceUpdate: Boolean,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val resources =
          griisImporter.fetchResourceList().filter {
            resourceName == null || resourceName == it.name
          }

      val updatedCount = resources.count { resource ->
        griisImporter.importResource(resource, forceUpdate)
      }

      val skippedCount = resources.size - updatedCount

      redirectAttributes.successMessage =
          "Imported $updatedCount and skipped $skippedCount GRIIS resources."
    } catch (e: Exception) {
      log.error("GRIIS import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToSpeciesHome()
  }

  @PostMapping("/importWcvpSpeciesList")
  @RequireGlobalRole([GlobalRole.SuperAdmin])
  fun importWcvpSpeciesList(
      @RequestParam url: URI,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      withDownloadedFile(url) { zipFilePath ->
        ZipFile(zipFilePath.toFile()).use { zipFile -> wcvpImporter.import(zipFile) }
      }

      redirectAttributes.successMessage = "WCVP species list imported successfully."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToSpeciesHome()
  }

  private fun <T> withDownloadedFile(url: URI, suffix: String = ".zip", func: (Path) -> T): T {
    val tempFile = kotlin.io.path.createTempFile(suffix = suffix)

    try {
      log.info("Copying $url to local filesystem: $tempFile")

      url.toURL().openStream().use { fileStream ->
        Files.copy(fileStream, tempFile, StandardCopyOption.REPLACE_EXISTING)
      }

      return func(tempFile)
    } finally {
      tempFile.deleteIfExists()
    }
  }

  private fun redirectToSpeciesHome() = "redirect:/admin/species"
}
