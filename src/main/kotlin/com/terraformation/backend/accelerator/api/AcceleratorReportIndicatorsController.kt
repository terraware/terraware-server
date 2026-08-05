package com.terraformation.backend.accelerator.api

import com.terraformation.backend.accelerator.db.ReportIndicatorStore
import com.terraformation.backend.api.AcceleratorEndpoint
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.CommonIndicatorId
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
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
class ReportIndicatorsController(private val indicatorStore: ReportIndicatorStore) {
  @ApiResponse200
  @GetMapping("/commonIndicators")
  @Operation(summary = "List all common indicators.")
  fun listCommonIndicators(): ListCommonIndicatorsResponsePayload {
    val models = indicatorStore.fetchAllCommonIndicators()
    return ListCommonIndicatorsResponsePayload(models.map { ExistingCommonIndicatorPayload(it) })
  }

  @ApiResponse200
  @PutMapping("/commonIndicators")
  @Operation(summary = "Insert common indicator, that projects will report on all future reports.")
  fun createCommonIndicator(
      @RequestBody @Valid payload: CreateCommonIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.createCommonIndicator(payload.indicator.toCommonIndicatorModel())
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @PostMapping("/commonIndicators/{indicatorId}")
  @Operation(summary = "Update one common indicator by ID.")
  fun updateCommonIndicator(
      @PathVariable indicatorId: CommonIndicatorId,
      @RequestBody payload: UpdateCommonIndicatorRequestPayload,
  ): SimpleSuccessResponsePayload {
    indicatorStore.updateCommonIndicator(indicatorId) { payload.indicator.toModel() }
    return SimpleSuccessResponsePayload()
  }

  @ApiResponse200
  @GetMapping("/autoCalculatedIndicators")
  @Operation(summary = "List all auto calculated indicators.")
  fun listAutoCalculatedIndicators(): ListAutoCalculatedIndicatorsResponsePayload {
    val indicators = indicatorStore.fetchAutoCalculatedIndicators()
    return ListAutoCalculatedIndicatorsResponsePayload(
        indicators.map { AutoCalculatedIndicatorPayload(it) }
    )
  }
}

data class AutoCalculatedIndicatorPayload(
    val indicator: AutoCalculatedIndicator,
    val active: Boolean = indicator.active,
    val category: IndicatorCategory = indicator.categoryId,
    val classId: IndicatorClass = indicator.classId,
    val description: String = indicator.description,
    val frequency: IndicatorFrequency? = indicator.frequencyId,
    val level: IndicatorLevel = indicator.levelId,
    val name: String = indicator.jsonValue,
    val notes: String? = indicator.notes,
    val precision: Int = indicator.precision,
    val primaryDataSource: String? = indicator.primaryDataSource,
    val refId: String = indicator.refId,
    val tfOwner: String? = indicator.tfOwner,
    val unit: String? = indicator.unit,
)

data class CreateCommonIndicatorRequestPayload(@field:Valid val indicator: NewIndicatorPayload)

data class ListCommonIndicatorsResponsePayload(
    val indicators: List<ExistingCommonIndicatorPayload>
) : SuccessResponsePayload

data class ListAutoCalculatedIndicatorsResponsePayload(
    val indicators: List<AutoCalculatedIndicatorPayload>
) : SuccessResponsePayload

data class UpdateCommonIndicatorRequestPayload(
    @field:Valid val indicator: ExistingCommonIndicatorPayload
)
