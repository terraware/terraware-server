package com.terraformation.backend.customer.api

import com.terraformation.backend.api.CustomerEndpoint
import com.terraformation.backend.api.SimpleSuccessResponsePayload
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.db.DisclaimerStore
import com.terraformation.backend.customer.model.UserDisclaimerModel
import io.swagger.v3.oas.annotations.Operation
import java.time.Instant
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@CustomerEndpoint
@RestController
@RequestMapping("/api/v1/disclaimer")
class DisclaimerController(private val disclaimerStore: DisclaimerStore) {
  @GetMapping
  @Operation(summary = "Fetch current disclaimer.")
  fun getDisclaimer(): GetDisclaimerResponse {
    val disclaimer = disclaimerStore.fetchCurrentDisclaimer()
    return GetDisclaimerResponse(disclaimer?.let { DisclaimerPayload(it) })
  }

  @Operation(summary = "Accept current disclaimer.")
  @PostMapping
  fun acceptDisclaimer(): SimpleSuccessResponsePayload {
    disclaimerStore.acceptCurrentDisclaimer()
    return SimpleSuccessResponsePayload()
  }
}

data class DisclaimerPayload(
    val content: String,
    val effectiveOn: Instant,
    val acceptedOn: Instant?,
) {
  constructor(
      model: UserDisclaimerModel
  ) : this(
      content = model.content,
      effectiveOn = model.effectiveOn,
      acceptedOn = model.acceptedOn,
  )
}

data class GetDisclaimerResponse(
    val disclaimer: DisclaimerPayload?,
) : SuccessResponsePayload
