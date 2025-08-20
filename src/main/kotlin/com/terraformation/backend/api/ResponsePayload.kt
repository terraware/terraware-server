package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonValue
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    enumAsRef = true,
    description = "Indicates of success or failure of the requested operation.",
)
enum class SuccessOrError {
  Ok,
  Error;

  @JsonValue override fun toString() = name.lowercase()
}

interface ResponsePayload {
  @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED) //
  val status: SuccessOrError
}

interface SuccessResponsePayload : ResponsePayload {
  override val status
    get() = SuccessOrError.Ok
}

data class ErrorDetails(val message: String)

interface ErrorResponsePayload : ResponsePayload {
  override val status
    get() = SuccessOrError.Error

  val error: ErrorDetails
}

@Schema(
    oneOf = [SuccessResponsePayload::class, ErrorResponsePayload::class],
    discriminatorMapping =
        [
            DiscriminatorMapping("ok", schema = SimpleSuccessResponsePayload::class),
            DiscriminatorMapping("error", schema = SimpleErrorResponsePayload::class),
        ],
    discriminatorProperty = "status",
)
interface SimpleResponsePayload : ResponsePayload

class SimpleSuccessResponsePayload : SuccessResponsePayload, SimpleResponsePayload

data class SimpleErrorResponsePayload(override val error: ErrorDetails) :
    ErrorResponsePayload, SimpleResponsePayload
