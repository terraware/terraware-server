package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.db.docprod.tables.pojos.DocumentTemplatesRow
import com.terraformation.backend.documentproducer.VariableService
import com.terraformation.backend.documentproducer.db.manifest.ManifestImporter
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin, GlobalRole.TFExpert])
@RequestMapping("/admin/document-producer")
class AdminDocumentProducerController(
    private val documentTemplatesDao: DocumentTemplatesDao,
    private val manifestImporter: ManifestImporter,
    private val variableService: VariableService,
) {
  @GetMapping
  fun getIndex(model: Model): String {
    model.addAttribute("documentTemplates", documentTemplatesDao.findAll().sortedBy { it.id })

    return "/admin/documentProducer"
  }

  @PostMapping("/createDocumentTemplate")
  fun createDocumentTemplate(
      @RequestParam name: String,
      model: Model,
      redirectAttributes: RedirectAttributes,
  ): String {
    val row = DocumentTemplatesRow(name = name)

    try {
      documentTemplatesDao.insert(row)
      redirectAttributes.successMessage = "Document Template ${row.id} added."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Failed to add document template: ${e.message}"
    }

    return redirectToDocumentProducer()
  }

  @PostMapping("/upgradeAllVariables")
  fun upgradeAllVariables(redirectAttributes: RedirectAttributes): String {
    try {
      variableService.upgradeAllVariables()
      redirectAttributes.successMessage = "Upgrades complete."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Failed to upgrade: ${e.message}"
    }

    return redirectToDocumentProducer()
  }

  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], path = ["/uploadAllVariables"])
  fun uploadAllVariables(
      @RequestPart("file") file: MultipartFile,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      file.inputStream.use { uploadStream ->
        val result = variableService.importAllVariables(uploadStream)
        if (result.errors.isEmpty()) {
          redirectAttributes.successMessage = "Imported variables."
        } else {
          redirectAttributes.failureMessage = "Failed to import variables."
          redirectAttributes.failureDetails = result.errors
        }
      }
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Error attempting to import variables: $e"
    }

    return redirectToDocumentProducer()
  }

  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], path = ["/uploadManifest"])
  fun uploadVariableManifest(
      @RequestPart("file") file: MultipartFile,
      @Schema(
          format = "int64",
          type = "integer",
          description = "The Document Template ID that this manifest is defined for",
      )
      @RequestPart("documentTemplateId")
      documentTemplateIdString: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    val documentTemplateId = DocumentTemplateId(documentTemplateIdString.toLong())

    try {
      file.inputStream.use { uploadStream ->
        val result = manifestImporter.import(documentTemplateId, uploadStream)
        if (result.errors.isEmpty()) {
          redirectAttributes.successMessage = "Imported manifest ${result.newVersion}."
        } else {
          redirectAttributes.failureMessage = "Failed to import manifest."
          redirectAttributes.failureDetails = result.errors
        }
      }
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Error attempting to import manifest: $e"
    }

    return redirectToDocumentProducer()
  }

  private fun redirectToDocumentProducer() = "redirect:/admin/document-producer"
}
