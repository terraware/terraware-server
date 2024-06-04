package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.docprod.DocumentTemplateId
import com.terraformation.backend.db.docprod.tables.daos.DocumentTemplatesDao
import com.terraformation.backend.db.docprod.tables.pojos.DocumentTemplatesRow
import com.terraformation.backend.documentproducer.db.manifest.ManifestImporter
import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
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
    private val manifestImporter: ManifestImporter,
    private val documentTemplatesDao: DocumentTemplatesDao,
) {
  /** Redirects /admin to /admin/ so relative URLs in the UI will work. */
  @GetMapping
  fun redirectToTrailingSlash(): String {
    return documentProducerAdminHome()
  }

  @GetMapping("/")
  fun getIndex(model: Model): String {
    model.addAttribute(
        "documentTemplates", documentTemplatesDao.findAll().sortedBy { it.id?.value })

    return "/admin/documentProducer"
  }

  @PostMapping("/createDocumentTemplate")
  fun createDocumentTemplate(
      @RequestParam name: String,
      model: Model,
      redirectAttributes: RedirectAttributes
  ): String {
    val row = DocumentTemplatesRow(name = name)

    try {
      documentTemplatesDao.insert(row)
      redirectAttributes.successMessage = "Document Template ${row.id} added."
    } catch (e: Exception) {
      redirectAttributes.failureMessage = "Failed to add document template: ${e.message}"
    }

    return documentProducerAdminHome()
  }

  @Operation(
      summary = "Upload a variable manifest.",
      description = "The uploaded file must be in CSV format.",
      externalDocs =
          ExternalDocumentation(
              description = "Current Google sheet",
              url =
                  "https://docs.google.com/spreadsheets/d/1PZTcDdWnegd5V03jvFguVHTBTb2sG3uzM4tmNhzvCUQ/edit#gid=1382800928"))
  @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], path = ["/uploadManifest"])
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = [Content(encoding = [Encoding(name = "file", contentType = "text/csv")])],
  )
  fun uploadVariableManifest(
      @RequestPart("file") file: MultipartFile,
      @Schema(
          format = "int64",
          type = "integer",
          description = "The Document Template ID that this manifest is defined for")
      @RequestPart("documentTemplateId")
      documentTemplateIdString: String,
      redirectAttributes: RedirectAttributes,
  ): String {
    val fileName = file.originalFilename ?: "manifest.csv"
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

    return documentProducerAdminHome()
  }

  private var RedirectAttributes.failureMessage: String?
    get() = flashAttributes["failureMessage"]?.toString()
    set(value) {
      addFlashAttribute("failureMessage", value)
    }

  private var RedirectAttributes.failureDetails: List<String>?
    get() {
      val attribute = flashAttributes["failureDetails"]
      return if (attribute is List<*>) {
        attribute.map { "$it" }
      } else {
        null
      }
    }
    set(value) {
      addFlashAttribute("failureDetails", value)
    }

  private var RedirectAttributes.successMessage: String?
    get() = flashAttributes["successMessage"]?.toString()
    set(value) {
      addFlashAttribute("successMessage", value)
    }

  /** Returns a redirect view name for an admin endpoint. */
  private fun redirect(endpoint: String) = "redirect:/admin$endpoint"

  // Convenience methods to redirect to the GET endpoint for each kind of thing.

  private fun documentProducerAdminHome() = redirect("/document-producer/")
}
