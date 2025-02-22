package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportMetricStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.StandardMetricId
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@AcceleratorEndpoint
@RequestMapping("/api/v1/accelerator/reports")
@RestController
class ReportsController(private val metricStore: ReportMetricStore) {
  @ApiResponse200
  @GetMapping("/standardMetrics")
  @Operation(summary = "List all standard metrics.")
  fun listStandardMetric(): ListStandardMetricsResponsePayload {
    val models = metricStore.fetchAllStandardMetrics()
    return ListStandardMetricsResponsePayload(models.map { ExistingStandardMetricPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/standardMetrics")
  @Operation(summary = "Insert standard metric, that projects will report on all future reports.")
  fun createStandardMetric(
      @RequestBody payload: CreateStandardMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    metricStore.createStandardMetric(payload.metric.toStandardMetricModel())
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @PostMapping("/standardMetrics/{metricId}")
  @Operation(summary = "Update one standard metric by ID.")
  fun updateStandardMetric(
      @PathVariable metricId: StandardMetricId,
      @RequestBody payload: UpdateStandardMetricRequestPayload,
  ): SimpleSuccessResponsePayload {
    metricStore.updateStandardMetric(metricId) { payload.metric.toModel() }
    return SimpleSuccessResponsePayload()
  }
}

data class CreateStandardMetricRequestPayload(val metric: NewMetricPayload)

data class ListStandardMetricsResponsePayload(val metrics: List<ExistingStandardMetricPayload>) :
    SuccessResponsePayload

data class UpdateStandardMetricRequestPayload(val metric: ExistingStandardMetricPayload)
