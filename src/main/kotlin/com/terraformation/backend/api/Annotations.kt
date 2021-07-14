package com.terraformation.backend.api

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "SeedBankApp")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SeedBankAppEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "DeviceManager")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class DeviceManagerAppEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "404",
    content =
        [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SimpleErrorResponsePayload::class))])
annotation class ApiResponse404(val description: String = "The requested resource was not found.")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "200",
    content =
        [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SimpleSuccessResponsePayload::class))])
annotation class ApiResponseSimpleSuccess(
    val description: String = "The requested operation succeeded."
)
