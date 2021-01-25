package com.terraformation.seedbank.api.annotation

import com.terraformation.seedbank.api.SimpleErrorResponsePayload
import com.terraformation.seedbank.api.SimpleSuccessResponsePayload
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "SeedBankApp")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SeedBankAppEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "404",
    content = [Content(schema = Schema(implementation = SimpleErrorResponsePayload::class))])
annotation class ApiResponse404(val description: String = "The requested resource was not found.")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "200",
    content = [Content(schema = Schema(implementation = SimpleSuccessResponsePayload::class))])
annotation class ApiResponseSimpleSuccess(
    val description: String = "The requested operation succeeded."
)
