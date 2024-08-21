package com.terraformation.backend.admin

import com.terraformation.backend.accelerator.migration.ProjectSetUpImporter
import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.importer.CsvImportFailedException
import com.terraformation.backend.log.perClassLogger
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminPdhController(
    private val projectSetUpImporter: ProjectSetUpImporter,
) {
  private val log = perClassLogger()

  @GetMapping("/pdh")
  fun pdhHome(): String {
    return "/admin/pdh"
  }

  @PostMapping("/uploadProjectSetUp")
  fun uploadProjectSetUp(
      @RequestPart file: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      file.inputStream.use { inputStream -> projectSetUpImporter.importCsv(inputStream) }

      redirectAttributes.successMessage = "Projects imported successfully."
    } catch (e: CsvImportFailedException) {
      redirectAttributes.failureMessage = e.message
      redirectAttributes.failureDetails = e.errors.map { "Row ${it.rowNumber}: ${it.message}" }
    } catch (e: Exception) {
      log.warn("Import failed", e)
      redirectAttributes.failureMessage = "Import failed: ${e.message}"
    }

    return redirectToPdhHome()
  }

  private fun redirectToPdhHome() = "redirect:/admin/pdh"
}
