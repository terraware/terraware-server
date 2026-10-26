package com.terraformation.backend.tracking.api

import com.terraformation.backend.api.ErrorDetails
import com.terraformation.backend.api.ErrorResponsePayload
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Returns the list of validation problems when a planting site map fails validation. This needs to
 * take precedence over the generic exception handlers, which would otherwise return an error
 * without the problems.
 */
@ControllerAdvice(
    assignableTypes = [DraftPlantingSitesController::class, PlantingSitesController::class]
)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PlantingSiteMapExceptionHandler {
  @ExceptionHandler
  fun handlePlantingSiteMapInvalidException(
      ex: PlantingSiteMapInvalidException
  ): ResponseEntity<PlantingSiteMapInvalidResponsePayload> {
    val payload =
        PlantingSiteMapInvalidResponsePayload(
            error = ErrorDetails(ex.message ?: "Planting site map is invalid"),
            problems = ex.problems.map { PlantingSiteValidationProblemPayload(it) },
        )

    return ResponseEntity.status(HttpStatus.CONFLICT).body(payload)
  }
}

data class PlantingSiteMapInvalidResponsePayload(
    override val error: ErrorDetails,
    val problems: List<PlantingSiteValidationProblemPayload>,
) : ErrorResponsePayload
