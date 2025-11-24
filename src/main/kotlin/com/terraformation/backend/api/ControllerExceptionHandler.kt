package com.terraformation.backend.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.PropertyBindingException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import com.opencsv.CSVWriter
import com.terraformation.backend.auth.CurrentUserHolder
import com.terraformation.backend.db.DuplicateEntityException
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.EntityStaleException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.OperationInProgressException
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.WebApplicationException
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Renders exceptions thrown by controllers as responses in the format defined by the seed bank API.
 */
@ControllerAdvice
class ControllerExceptionHandler : ResponseEntityExceptionHandler() {

  private val csvMediaType = MediaType("text", "csv", StandardCharsets.UTF_8)

  @ExceptionHandler
  fun handleDuplicateEntityException(
      ex: DuplicateEntityException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(ex.message, HttpStatus.CONFLICT, request)
  }

  @ExceptionHandler
  fun handleEntityNotFoundException(
      ex: EntityNotFoundException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(ex.message, HttpStatus.NOT_FOUND, request)
  }

  @ExceptionHandler
  fun handleEntityStaleException(ex: EntityStaleException, request: WebRequest): ResponseEntity<*> {
    return simpleErrorResponse(ex.message, HttpStatus.PRECONDITION_FAILED, request)
  }

  @ExceptionHandler
  fun handleMismatchedStateException(
      ex: MismatchedStateException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(ex.message, HttpStatus.CONFLICT, request)
  }

  @ExceptionHandler
  fun handleOperationInProgressException(
      ex: OperationInProgressException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(ex.message, HttpStatus.LOCKED, request)
  }

  @ExceptionHandler
  fun handleIllegalArgumentException(
      ex: IllegalArgumentException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(
        ex.message ?: "An internal error has occurred.",
        HttpStatus.BAD_REQUEST,
        request,
    )
  }

  @ExceptionHandler
  fun handleUnsupportedMediaTypeException(
      ex: UnsupportedMediaTypeException,
      request: WebRequest,
  ): ResponseEntity<*> {
    return simpleErrorResponse(
        ex.message ?: "Unsupported media type.",
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        request,
    )
  }

  @ExceptionHandler
  fun handleWebApplicationException(
      ex: WebApplicationException,
      request: WebRequest,
  ): ResponseEntity<*> {
    return simpleErrorResponse(
        ex.message ?: "An internal error has occurred.",
        HttpStatus.valueOf(ex.response.status),
        request,
    )
  }

  @ExceptionHandler
  fun handleGenericSpringResponseStatusException(
      ex: ResponseStatusException,
      request: WebRequest,
  ): ResponseEntity<*> {
    val description = request.getDescription(false)
    controllerLogger(ex)
        .warn("Generic response exception thrown on $description use ClientFacingException", ex)

    return simpleErrorResponse(ex.message, ex.statusCode, request)
  }

  /**
   * Handles exceptions that indicate the user didn't have permission to do something. These are
   * rendered as HTTP 403 Forbidden responses. The exception message, which has a more precise
   * description of what permission was missing, is passed on to the client.
   */
  @ExceptionHandler
  fun handleAccessDeniedException(
      ex: AccessDeniedException,
      request: WebRequest,
  ): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(ex.localizedMessage, HttpStatus.FORBIDDEN, request)
  }

  @ExceptionHandler
  fun handleUnknownException(ex: Exception, request: WebRequest): ResponseEntity<*> {
    logError(ex, request)
    return simpleErrorResponse(
        "An internal error has occurred.",
        HttpStatus.INTERNAL_SERVER_ERROR,
        request,
    )
  }

  /**
   * Returns an actionable error message to the client if the request has a malformed path or query
   * string parameter. The default behavior is to return HTTP 400 with no explanation of what the
   * problem is.
   */
  @ExceptionHandler
  fun handleMethodArgumentTypeMismatchException(
      ex: MethodArgumentTypeMismatchException,
      request: WebRequest,
  ): ResponseEntity<*> {
    val parameter = ex.parameter
    val parameterName = parameter.parameterName

    // We want the name of the parameter in the API schema, which might not be the same as the name
    // of the controller method parameter. Various annotations can specify the name in the schema.
    // Most of them, if no name is specified, default to a value of ""; in that case we want the
    // name of the method parameter.
    fun nameFromSchema(nameFromAnnotation: String?): String {
      return when {
        !nameFromAnnotation.isNullOrEmpty() -> nameFromAnnotation
        parameterName != null -> parameterName
        else -> ""
      }
    }

    val pathVariable = parameter.getParameterAnnotation(PathVariable::class.java)
    if (pathVariable != null) {
      return simpleErrorResponse(
          "Invalid value for URL path element ${nameFromSchema(pathVariable.name)}",
          HttpStatus.BAD_REQUEST,
          request,
      )
    }

    val queryParamName =
        parameter.getParameterAnnotation(QueryParam::class.java)?.value
            ?: parameter.getParameterAnnotation(RequestParam::class.java)?.value
    if (queryParamName != null) {
      return simpleErrorResponse(
          "Invalid value for query string parameter ${nameFromSchema(queryParamName)}",
          HttpStatus.BAD_REQUEST,
          request,
      )
    }

    return simpleErrorResponse("Invalid value in request URL", HttpStatus.BAD_REQUEST, request)
  }

  /**
   * Handles validation failures on request payloads. The payload argument to a controller method
   * needs to be annotated with `@RequestBody` and `@Valid`, at which point the `jakarta.validation`
   * annotations on the fields in the payload class are evaluated. (The annotations need to be on
   * the underlying fields, not the getters: that is, `@field:NotEmpty` rather than `@NotEmpty`.)
   */
  override fun handleMethodArgumentNotValid(
      ex: MethodArgumentNotValidException,
      headers: HttpHeaders,
      status: HttpStatusCode,
      request: WebRequest,
  ): ResponseEntity<Any> {
    val fieldName = ex.fieldError?.field ?: "field"
    val message = ex.fieldError?.defaultMessage ?: "invalid"
    return simpleErrorResponse(
        "Field value invalid: $fieldName $message",
        HttpStatus.BAD_REQUEST,
        request,
    )
  }

  /** Returns an actionable error message if the client submits a malformed request payload. */
  override fun handleHttpMessageNotReadable(
      ex: HttpMessageNotReadableException,
      headers: HttpHeaders,
      status: HttpStatusCode,
      request: WebRequest,
  ): ResponseEntity<Any> {
    val cause = ex.cause
    if (cause is JsonMappingException) {
      // The exception has a "path" which is the JSON parser's position in the parse tree. Array
      // fields are represented as two path elements, one with the field name and the next with the
      // array index; we want to render that as "fieldName[index]". We do it with a hack: it gets
      // rendered as "fieldName.[index]" first, and then we get rid of the period.
      //
      // An empty path means the request was malformed to the point the parser couldn't even figure
      // out how to start traversing it.
      if (cause.path.isEmpty()) {
        return simpleErrorResponse(
            "Unable to parse request payload",
            HttpStatus.BAD_REQUEST,
            request,
        )
      }

      val path =
          cause.path
              .joinToString(".") { reference ->
                val arrayIndex = if (reference.index >= 0) "[${reference.index}]" else ""
                val fieldName = reference.fieldName ?: ""
                "${fieldName}$arrayIndex"
              }
              .replace(".[", "[")

      val message =
          when (cause) {
            is InvalidNullException -> "Field value cannot be null"
            is InvalidFormatException -> "Field value has incorrect format"
            is InvalidTypeIdException -> getMessage(cause)
            is PropertyBindingException -> "Unrecognized field"
            is MismatchedInputException -> "Required field not present or invalid"
            is ValueInstantiationException -> "Field value invalid"
            else -> cause.originalMessage ?: "Field value invalid"
          }

      return simpleErrorResponse("$message: $path", status, request)
    } else {
      return simpleErrorResponse(ex.localizedMessage, status, request)
    }
  }

  /**
   * When deserialization fails because there's a polymorphic field in a request payload and the
   * identifier field that tells Jackson which concrete class to use is missing or invalid, returns
   * an error message that includes the name of the identifier field.
   */
  private fun getMessage(cause: InvalidTypeIdException): String {
    val typeIdProperty =
        cause.baseType.rawClass.getAnnotation(JsonTypeInfo::class.java)?.property
            ?: "type identifier"

    return if (cause.typeId != null) {
      "Unrecognized value ${cause.typeId} for $typeIdProperty in field"
    } else {
      "Missing $typeIdProperty in field"
    }
  }

  override fun handleMissingServletRequestParameter(
      ex: MissingServletRequestParameterException,
      headers: HttpHeaders,
      status: HttpStatusCode,
      request: WebRequest,
  ): ResponseEntity<Any> {
    return simpleErrorResponse(
        "Missing required parameter: ${ex.parameterName}",
        HttpStatus.BAD_REQUEST,
        request,
    )
  }

  private fun controllerLogger(ex: Exception): Logger {
    val classToUseForLogMessage = ex.stackTrace.getOrNull(0)?.className ?: javaClass.name
    return LoggerFactory.getLogger(classToUseForLogMessage)
  }

  private fun logError(ex: Exception, request: WebRequest) {
    val requestDescription = request.getDescription(false)
    val userDescription = CurrentUserHolder.getCurrentUser()?.description ?: "unauthenticated user"

    controllerLogger(ex)
        .error(
            "${ex.javaClass.simpleName} thrown while handling request $requestDescription " +
                "for $userDescription: ${ex.message}",
            ex,
        )
  }

  /**
   * Returns an error response in a format the client has indicated it is willing to accept. This
   * uses the first acceptable content type from the following list:
   * 1. `application/json`. The response uses the server's documented JSON error format.
   * 2. `text/plain`. The response body is the error message.
   * 3. `text/csv`. The response is a CSV document with `status` and `message` columns.
   * 4. None of the above. The response has an empty body.
   *
   * TODO: For clients that want HTML, render this using a custom error template.
   */
  private fun simpleErrorResponse(
      message: String,
      status: HttpStatusCode,
      request: WebRequest,
  ): ResponseEntity<Any> {
    val acceptHeaders = request.getHeaderValues(HttpHeaders.ACCEPT) ?: emptyArray()
    val acceptedTypes = MediaType.parseMediaTypes(acceptHeaders.toList())
    val headers = HttpHeaders()

    return if (acceptedTypes.any { it.isCompatibleWith(MediaType.APPLICATION_JSON) }) {
      ResponseEntity(SimpleErrorResponsePayload(ErrorDetails(message = message)), status)
    } else if (acceptedTypes.any { it.isCompatibleWith(MediaType.TEXT_PLAIN) }) {
      headers.contentType = MediaType.TEXT_PLAIN
      ResponseEntity(message, headers, status)
    } else if (acceptedTypes.any { it.isCompatibleWith(csvMediaType) }) {
      headers.contentType = csvMediaType
      val outputStream = ByteArrayOutputStream()
      CSVWriter(OutputStreamWriter(outputStream)).use { csvWriter ->
        csvWriter.writeNext(arrayOf("status", "message"))
        csvWriter.writeNext(arrayOf("error", message))
      }
      ResponseEntity(outputStream.toByteArray(), headers, status)
    } else {
      ResponseEntity(byteArrayOf(), status)
    }
  }
}
