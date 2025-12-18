package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportMetricStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.accelerator.SystemMetric
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
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
      @RequestBody @Valid payload: CreateStandardMetricRequestPayload,
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

  @ApiResponse200
  @GetMapping("/systemMetrics")
  @Operation(summary = "List all system metrics.")
  fun listSystemMetrics(): ListSystemMetricsResponsePayload {
    val metrics = metricStore.fetchSystemMetrics()
    return ListSystemMetricsResponsePayload(metrics.map { SystemMetricPayload(it) })
  }
}

data class SystemMetricPayload(
    val metric: SystemMetric,
    val name: String = metric.jsonValue,
    val description: String = metric.description,
    val component: MetricComponent = metric.componentId,
    val type: MetricType = metric.typeId,
    val reference: String = metric.reference,
)

data class CreateStandardMetricRequestPayload(@field:Valid val metric: NewMetricPayload)

data class ListStandardMetricsResponsePayload(val metrics: List<ExistingStandardMetricPayload>) :
    SuccessResponsePayload

data class ListSystemMetricsResponsePayload(val metrics: List<SystemMetricPayload>) :
    SuccessResponsePayload

data class UpdateStandardMetricRequestPayload(
    @field:Valid val metric: ExistingStandardMetricPayload
)
