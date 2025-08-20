package com.terraformation.backend.report.render

import com.opencsv.CSVWriter
import com.terraformation.backend.api.writeNext
import com.terraformation.backend.customer.db.OrganizationStore
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.report.SeedFundReportFileService
import com.terraformation.backend.report.db.SeedFundReportStore
import com.terraformation.backend.report.model.SeedFundReportBodyModel
import com.terraformation.backend.report.model.SeedFundReportBodyModelV1
import com.terraformation.backend.report.model.SeedFundReportModel
import jakarta.inject.Named
import java.io.StringWriter
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine

@Named
class SeedFundReportRenderer(
    private val organizationStore: OrganizationStore,
    private val seedFundReportFileService: SeedFundReportFileService,
    private val seedFundReportStore: SeedFundReportStore,
    private val templateEngine: SpringTemplateEngine,
) {
  fun renderReportHtml(reportId: SeedFundReportId): String {
    val report = seedFundReportStore.fetchOneById(reportId)

    return renderReportHtml(report)
  }

  fun renderReportHtml(report: SeedFundReportModel): String {
    val reportId = report.metadata.id

    val files = seedFundReportFileService.listFiles(reportId)
    val photos = seedFundReportFileService.listPhotos(reportId)
    val organization = organizationStore.fetchOneById(report.metadata.organizationId)
    val context = Context()

    val reportBodyLatestVersion = report.body.toLatestVersion()
    val numFacilitiesSelected =
        mapOf(
            "seedBanks" to reportBodyLatestVersion.seedBanks.count { it.selected },
            "nurseries" to reportBodyLatestVersion.nurseries.count { it.selected },
            "plantingSites" to reportBodyLatestVersion.plantingSites.count { it.selected },
        )

    context.setVariable("body", report.body)
    context.setVariable("numFacilitiesSelected", numFacilitiesSelected)
    context.setVariable("files", files)
    context.setVariable("metadata", report.metadata)
    context.setVariable("organization", organization)
    context.setVariable("photos", photos)

    return when (report.body) {
      is SeedFundReportBodyModelV1 -> {
        // Render best months as a list of English month names.
        context.setVariable(
            "bestMonths",
            report.body.annualDetails?.bestMonthsForObservation?.sorted()?.joinToString {
              Month.of(it).getDisplayName(TextStyle.FULL, Locale.US)
            },
        )
        templateEngine.process("/reports/v1/index.html", context)
      }
    }
  }

  fun renderReportCsv(reportId: SeedFundReportId): String {
    val report = seedFundReportStore.fetchOneById(reportId)

    return renderReportCsv(report)
  }

  fun renderReportCsv(report: SeedFundReportModel): String {
    val body: SeedFundReportBodyModel = report.body
    val columnValues: List<Pair<String, Any?>> =
        when (body) {
          is SeedFundReportBodyModelV1 -> {
            val nurseries = body.nurseries.filter { it.selected }
            val plantingSites = body.plantingSites.filter { it.selected }
            val seedBanks = body.seedBanks.filter { it.selected }
            val allWorkers =
                nurseries.map { it.workers } +
                    plantingSites.map { it.workers } +
                    seedBanks.map { it.workers }

            listOf(
                "Deal ID" to null,
                "Organization Name" to body.organizationName,
                "Seed Bank: Count" to seedBanks.size,
                "Seed Bank: Seeds Stored" to seedBanks.sumOf { it.totalSeedsStored },
                "Nursery: Count" to nurseries.size,
                "Nursery: Plants/Trees Propagated" to nurseries.sumOf { it.totalPlantsPropagated },
                "Nursery: Mortality Rate (%)" to
                    weightedAverage(nurseries.map { it.mortalityRate to it.totalPlantsPropagated }),
                "Planted: Site Count" to plantingSites.size,
                "Project Hectares" to plantingSites.sumOf { it.totalPlantingSiteArea ?: 0 },
                "Planted: Ha to Date" to plantingSites.sumOf { it.totalPlantedArea ?: 0 },
                "Planted: Trees" to plantingSites.sumOf { it.totalTreesPlanted ?: 0 },
                "Planted: Non-Trees" to plantingSites.sumOf { it.totalPlantsPlanted ?: 0 },
                "Planted: Mortality Rate (%)" to
                    weightedAverage(
                        plantingSites.map { site ->
                          val weight =
                              (site.totalPlantsPlanted ?: 0) + (site.totalTreesPlanted ?: 0)
                          (site.mortalityRate ?: 0) to weight.toLong()
                        }
                    ),
                "Planted: Species" to
                    plantingSites
                        .flatMap { it.species.map { species -> species.scientificName } }
                        .distinct()
                        .sorted()
                        .joinToString(","),
                "Planted: Best Months to Monitor Plantings" to
                    body.annualDetails?.bestMonthsForObservation?.sorted()?.joinToString(";") {
                        monthNumber ->
                      Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.US)
                    },
                "# of workers for TF projects" to allWorkers.sumOf { it.paidWorkers ?: 0 },
                "# of workers who are women for TF projects" to
                    allWorkers.sumOf { it.femalePaidWorkers ?: 0 },
                "# of volunteers for TF projects" to allWorkers.sumOf { it.volunteers ?: 0 },
                "Summary of progress" to body.summaryOfProgress,
            )
          }
        }

    val stringWriter = StringWriter()

    CSVWriter(
            stringWriter,
            CSVWriter.DEFAULT_SEPARATOR,
            CSVWriter.DEFAULT_QUOTE_CHARACTER,
            CSVWriter.DEFAULT_ESCAPE_CHARACTER,
            CSVWriter.RFC4180_LINE_END,
        )
        .use { csvWriter ->
          csvWriter.writeNext(columnValues.map { it.first })
          csvWriter.writeNext(columnValues.map { it.second })
        }

    return stringWriter.toString()
  }

  private fun weightedAverage(valuesAndWeights: List<Pair<Int, Long>>): Long {
    val weightedValues = valuesAndWeights.sumOf { it.first * it.second }
    val totalWeights = valuesAndWeights.sumOf { it.second }

    return if (totalWeights > 0L) weightedValues / totalWeights else 0L
  }
}
