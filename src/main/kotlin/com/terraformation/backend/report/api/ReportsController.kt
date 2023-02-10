package com.terraformation.backend.report.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.UserStore
import com.terraformation.backend.customer.model.IndividualUser
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.ReportStatus
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.report.ReportService
import com.terraformation.backend.report.db.ReportStore
import com.terraformation.backend.report.model.ReportMetadata
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RequestMapping("/api/v1/reports")
@RestController
class ReportsController(
    private val reportService: ReportService,
    private val reportStore: ReportStore,
    private val userStore: UserStore,
) {
  @GetMapping
  fun listReports(
      @RequestParam(required = true) organizationId: OrganizationId
  ): ListReportsResponsePayload {
    val reports =
        reportStore.fetchMetadataByOrganization(organizationId).map { metadata ->
          val lockedByName =
              metadata.lockedBy?.let { (userStore.fetchOneById(it) as? IndividualUser)?.fullName }
          ListReportsResponseElement(metadata, lockedByName)
        }

    return ListReportsResponsePayload(reports)
  }

  @GetMapping("/{id}")
  fun getReport(@PathVariable("id") id: ReportId): GetReportResponsePayload {
    val model = reportService.fetchOneById(id)
    val lockedByName =
        model.metadata.lockedBy?.let { (userStore.fetchOneById(it) as? IndividualUser)?.fullName }
    val reportPayload = GetReportPayload.of(model, lockedByName)

    return GetReportResponsePayload(reportPayload)
  }

  @PostMapping("/{id}/lock")
  fun lockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.lock(id)

    return SimpleSuccessResponsePayload()
  }

  @PostMapping("/{id}/lock/force")
  fun forceLockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.lock(id, true)

    return SimpleSuccessResponsePayload()
  }

  @PostMapping("/{id}/unlock")
  fun unlockReport(@PathVariable("id") id: ReportId): SimpleSuccessResponsePayload {
    reportStore.unlock(id)

    return SimpleSuccessResponsePayload()
  }

  @PutMapping("/{id}")
  fun updateReport(
      @PathVariable("id") id: ReportId,
      @RequestBody payload: PutReportRequestPayload
  ): SimpleSuccessResponsePayload {
    reportService.update(id) { payload.report.copyTo(it) }

    return SimpleSuccessResponsePayload()
  }
}

data class ListReportsResponseElement(
    override val id: ReportId,
    override val lockedByName: String?,
    override val lockedByUserId: UserId?,
    override val lockedTime: Instant?,
    override val quarter: Int,
    override val status: ReportStatus,
    override val year: Int
) : ReportMetadataFields {
  constructor(
      metadata: ReportMetadata,
      lockedByName: String?
  ) : this(
      id = metadata.id,
      lockedByName = lockedByName,
      lockedByUserId = metadata.lockedBy,
      lockedTime = metadata.lockedTime,
      quarter = metadata.quarter,
      status = metadata.status,
      year = metadata.year,
  )
}

data class GetReportResponsePayload(
    val report: GetReportPayload,
) : SuccessResponsePayload

data class ListReportsResponsePayload(
    val reports: List<ListReportsResponseElement>,
) : SuccessResponsePayload

data class PutReportRequestPayload(
    val report: PutReportPayload,
)
