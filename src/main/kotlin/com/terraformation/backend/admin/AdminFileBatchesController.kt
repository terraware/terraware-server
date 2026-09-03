package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.db.default_schema.FileBatchType
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.daos.OrganizationsDao
import com.terraformation.backend.file.FileService
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.tracking.OrganizationMediaService
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
@RequestMapping("/admin/fileBatches")
@RequireGlobalRole([GlobalRole.SuperAdmin])
@Validated
class AdminFileBatchesController(
    private val fileService: FileService,
    private val organizationMediaService: OrganizationMediaService,
    private val organizationsDao: OrganizationsDao,
) {
  private val log = perClassLogger()

  @GetMapping
  fun fileBatchesHome(model: Model): String {
    model.addAttribute("organizations", organizationsDao.findAll().sortedBy { it.id })
    model.addAttribute("fileBatchTypes", FileBatchType.entries)

    return "/admin/fileBatches"
  }

  /**
   * Uploads a batch of organization media files. Each file may be given a content type that
   * overrides the one the browser detected, which allows uploading files whose content types
   * clients can't declare directly, such as the additional files for splat generation.
   *
   * The content type for a file is the element of [contentTypes] at the same position as the file
   * in [files]; browsers submit an entry for every row of the form, including rows where no file
   * was chosen, so the two lists stay aligned.
   */
  @PostMapping
  fun uploadFileBatch(
      @RequestParam organizationId: OrganizationId,
      @RequestParam type: FileBatchType,
      @RequestPart("files", required = false) files: List<MultipartFile>?,
      @RequestParam("contentTypes", required = false) contentTypes: List<String>?,
      redirectAttributes: RedirectAttributes,
  ): String {
    val fileList = files ?: emptyList()
    val contentTypeList = contentTypes ?: emptyList()

    if (fileList.size != contentTypeList.size) {
      redirectAttributes.failureMessage =
          "Got ${fileList.size} file(s) but ${contentTypeList.size} content type(s); can't tell " +
              "which content type goes with which file."
      return redirectToFileBatchesHome()
    }

    val uploads = fileList.zip(contentTypeList).filter { (file, _) -> !file.isEmpty }

    if (uploads.isEmpty()) {
      redirectAttributes.failureMessage = "No files were selected."
      return redirectToFileBatchesHome()
    }

    try {
      val fileBatchId = fileService.createFileBatch(type)

      val details = uploads.map { (file, contentType) ->
        val fileId =
            organizationMediaService.upload(
                organizationId = organizationId,
                file = file,
                caption = null,
                fileBatchId = fileBatchId,
                contentType = contentType.ifBlank { null },
            )

        "File $fileId: ${file.originalFilename} (${contentType.ifBlank { "detected" }})"
      }

      fileService.finishUploadingFileBatch(fileBatchId)

      redirectAttributes.successMessage =
          "Uploaded ${uploads.size} file(s) as $type batch $fileBatchId."
      redirectAttributes.successDetails = details
    } catch (e: Exception) {
      log.error("Error uploading file batch", e)
      redirectAttributes.failureMessage = "Failed to upload file batch: ${e.message}"
    }

    return redirectToFileBatchesHome()
  }

  private fun redirectToFileBatchesHome() = "redirect:/admin/fileBatches"
}
