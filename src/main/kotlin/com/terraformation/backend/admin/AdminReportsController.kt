package com.terraformation.backend.admin

import com.terraformation.backend.api.RequireGlobalRole
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.SeedFundReportService
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.render.SeedFundReportRenderer
import jakarta.ws.rs.Produces
import java.nio.charset.StandardCharsets
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin")
@RequireGlobalRole([GlobalRole.SuperAdmin, GlobalRole.AcceleratorAdmin])
@Validated
class AdminReportsController(
    private val seedFundReportRenderer: SeedFundReportRenderer,
    private val seedFundReportService: SeedFundReportService,
    private val seedFundReportStore: SeedFundReportStore,
) {
  private val log = perClassLogger()

  @GetMapping("/report/{id}/index.html")
  @Produces("text/html")
  fun getReportHtml(@PathVariable("id") reportId: SeedFundReportId): ResponseEntity<String> {
    return ResponseEntity.ok(seedFundReportRenderer.renderReportHtml(reportId))
  }

  @GetMapping("/report/{id}/report.csv")
  @Produces("text/csv")
  fun getReportCsv(@PathVariable("id") reportId: SeedFundReportId): ResponseEntity<String> {
    return ResponseEntity.ok()
        .contentType(MediaType("text", "csv", StandardCharsets.UTF_8))
        .body(seedFundReportRenderer.renderReportCsv(reportId))
  }

  @PostMapping("/createReport")
  fun createReport(
      @RequestParam organizationId: OrganizationId,
      redirectAttributes: RedirectAttributes,
  ): String {
    try {
      val metadata = seedFundReportService.create(organizationId)
      redirectAttributes.successMessage = "Report ${metadata.id} created."
    } catch (e: Exception) {
      log.warn("Report creation failed", e)
      redirectAttributes.failureMessage = "Report creation failed: ${e.message}"
    }

    return redirectToOrganization(organizationId)
  }

  @PostMapping("/deleteReport")
  fun deleteReport(
      @RequestParam organizationId: OrganizationId,
      @RequestParam reportId: SeedFundReportId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { deleteSeedFundReport(reportId) }

    try {
      seedFundReportStore.delete(reportId)
      redirectAttributes.successMessage = "Deleted report."
    } catch (e: Exception) {
      log.warn("Report deletion failed", e)
      redirectAttributes.failureMessage = "Report deletion failed: ${e.message}"
    }

    return redirectToOrganization(organizationId)
  }

  @PostMapping("/exportReport")
  fun exportReport(
      @RequestParam organizationId: OrganizationId,
      @RequestParam reportId: SeedFundReportId,
      redirectAttributes: RedirectAttributes,
  ): String {
    requirePermissions { createSeedFundReport(organizationId) }

    try {
      seedFundReportService.exportToGoogleDrive(reportId)
      redirectAttributes.successMessage = "Exported report to Google Drive."
    } catch (e: Exception) {
      log.warn("Report export failed", e)
      redirectAttributes.failureMessage = "Report export failed: ${e.message}"
    }

    return redirectToOrganization(organizationId)
  }

  private fun redirectToOrganization(organizationId: OrganizationId) =
      "redirect:/admin/organization/$organizationId"
}
