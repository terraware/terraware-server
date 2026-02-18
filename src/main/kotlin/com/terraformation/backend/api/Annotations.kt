package com.terraformation.backend.api

import com.terraformation.backend.db.default_schema.GlobalRole
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Encoding
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.core.annotation.AliasFor
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
@Tag(name = "Support")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SupportEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Tracking")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TrackingEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Accelerator")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class AcceleratorEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "Funder")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class FunderEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Tag(name = "internal")
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class InternalEndpoint

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@ApiResponse(
    content =
        [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SimpleErrorResponsePayload::class),
            )
        ]
)
annotation class ApiResponseSimpleError(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "responseCode")
    val responseCode: String
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(responseCode = "200")
annotation class ApiResponse200(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The requested operation succeeded."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(responseCode = "202")
annotation class ApiResponse202(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String =
        "The requested resource is not available yet, but should become available later."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "400")
annotation class ApiResponse400(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The request was not valid."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "403")
annotation class ApiResponse403(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The request was not permitted."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "404")
annotation class ApiResponse404(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The requested resource was not found."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "409")
annotation class ApiResponse409(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The request would cause a conflict."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "412")
annotation class ApiResponse412(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The requested resource has a newer version and was not updated."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "413")
annotation class ApiResponse413(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The request was too large."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "415")
annotation class ApiResponse415(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The media type is not supported."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponseSimpleError(responseCode = "422")
annotation class ApiResponse422(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The requested content could not be processed."
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "200",
    content =
        [
            Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = SimpleSuccessResponsePayload::class),
            )
        ],
)
annotation class ApiResponseSimpleSuccess(
    @get:AliasFor(annotation = ApiResponse::class, attribute = "description")
    val description: String = "The requested operation succeeded."
)

/**
 * Requires the user to have one of the specified global roles in order to access an endpoint. If
 * this annotation is used at the class level, it applies to all the handler methods in the class.
 * If it is used at both the class and method levels, the method-level annotation takes precedence.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class RequireGlobalRole(val roles: Array<GlobalRole>)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@ApiResponse(
    responseCode = "200",
    description = "The photo was successfully retrieved.",
    content =
        [
            Content(
                schema = Schema(type = "string", format = "binary"),
                mediaType = MediaType.IMAGE_JPEG_VALUE,
            ),
            Content(
                schema = Schema(type = "string", format = "binary"),
                mediaType = MediaType.IMAGE_PNG_VALUE,
            ),
        ],
)
annotation class ApiResponse200Photo

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
@RequestBody(
    content =
        [
            Content(
                encoding =
                    [
                        Encoding(
                            name = "file",
                            contentType =
                                "${MediaType.IMAGE_JPEG_VALUE}, " +
                                    "${MediaType.IMAGE_PNG_VALUE}, " +
                                    "image/heic",
                        )
                    ]
            )
        ]
)
annotation class RequestBodyPhotoFile

/** Allows payload fields to have blank strings during deserialization */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FIELD)
annotation class AllowBlankString
