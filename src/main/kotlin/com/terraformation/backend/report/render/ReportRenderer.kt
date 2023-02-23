package com.terraformation.backend.report.render

import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.report.ReportFileService
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportBodyModelV1
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Named
import org.thymeleaf.context.Context
import org.thymeleaf.spring5.SpringTemplateEngine

@Named
class ReportRenderer(
    private val organizationStore: OrganizationStore,
    private val reportFileService: ReportFileService,
    private val reportStore: ReportStore,
    private val templateEngine: SpringTemplateEngine,
) {
  fun renderReportHtml(reportId: ReportId): String {
    val report = reportStore.fetchOneById(reportId)
    val files = reportFileService.listFiles(reportId)
    val photos = reportFileService.listPhotos(reportId)
    val organization = organizationStore.fetchOneById(report.metadata.organizationId)
    val context = Context()

    context.setVariable("body", report.body)
    context.setVariable("files", files)
    context.setVariable("metadata", report.metadata)
    context.setVariable("organization", organization)
    context.setVariable("photos", photos)

    return when (report.body) {
      is ReportBodyModelV1 -> {
        // Render best months as a list of English month names.
        context.setVariable(
            "bestMonths",
            report.body.annualDetails?.bestMonthsForObservation?.sorted()?.joinToString {
              Month.of(it).getDisplayName(TextStyle.FULL, Locale.US)
            })
        templateEngine.process("/reports/v1/index.html", context)
      }
    }
  }
}
