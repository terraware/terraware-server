package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.TrackingEndpoint
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.tracking.db.PlantingSiteStore
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import java.math.BigDecimal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RequestMapping("/api/v1/tracking/strata")
@RestController
@TrackingEndpoint
class StrataController(private val plantingSiteStore: PlantingSiteStore) {
  @Operation(summary = "Updates the settings of a stratum.")
  @PutMapping("/{id}")
  fun updateStratum(
      @PathVariable id: StratumId,
      @RequestBody @Valid payload: UpdateStratumRequestPayload,
  ): SimpleSuccessResponsePayload {
    plantingSiteStore.updateStratum(id, payload::applyTo)

    return SimpleSuccessResponsePayload()
  }
}

data class UpdateStratumRequestPayload(
    @Min(1) val initialPlantingDensity: BigDecimal,
    @Min(1) val targetPlantDensity: BigDecimal?,
) {
  fun applyTo(row: StrataRow): StrataRow =
      row.copy(
          initialPlantingDensity = initialPlantingDensity,
          targetPlantDensity = targetPlantDensity,
      )
}
