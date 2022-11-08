package com.terraformation.backend.api

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Nursery")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NurseryEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "SeedBankApp")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SeedBankAppEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "DeviceManager")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class DeviceManagerAppEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Customer")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CustomerEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Search")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SearchEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Tracking")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrackingEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@ApiResponse(
    content =
        [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SimpleErrorResponsePayload::class))])
annotation class ApiResponseSimpleError(val responseCode: String)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "404")
annotation class ApiResponse404(val description: String = "The requested resource was not found.")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "409")
annotation class ApiResponse409(val description: String = "The request would cause a conflict.")

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "412")
annotation class ApiResponse412(
    val description: String = "The requested resource has a newer version and was not updated."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "413")
annotation class ApiResponse413(val description: String = "The request was too large.")

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

/**
 * Requires the user to be an admin or owner in at least one organization in order to access an
 * endpoint. If this annotation is used at the class level, it applies to all the handler methods in
 * the class.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class RequireExistingAdminRole
