package com.terraformation.seedbank.api

import com.terraformation.seedbank.services.perClassLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Renders exceptions thrown by controllers as responses in the format defined by the seed bank API.
 */
@ControllerAdvice
class ControllerExceptionHandler : ResponseEntityExceptionHandler() {
  private val log = perClassLogger()

  @ExceptionHandler(ClientFacingException::class)
  fun handleClientFacingException(
      ex: ClientFacingException,
      request: WebRequest
  ): ResponseEntity<*> {
    return simpleErrorResponse(ex.message, ex.status, request)
  }

  @ExceptionHandler(ResponseStatusException::class)
  fun handleGenericSpringResponseStatusException(
      ex: ResponseStatusException,
      request: WebRequest
  ): ResponseEntity<*> {
    val description = request.getDescription(false)
    controllerLogger(ex)
        .warn("Generic response exception thrown on $description use ClientFacingException", ex)
    return simpleErrorResponse(ex.message ?: ex.status.reasonPhrase, ex.status, request)
  }

  @ExceptionHandler(Exception::class)
  fun handleUnknownException(ex: Exception, request: WebRequest): ResponseEntity<*> {
    val description = request.getDescription(false)
    controllerLogger(ex).error("Controller threw exception on $description", ex)

    return simpleErrorResponse(
        "An internal error has occurred.", HttpStatus.INTERNAL_SERVER_ERROR, request)
  }

  private fun controllerLogger(ex: Exception): Logger {
    val classToUseForLogMessage = ex.stackTrace.getOrNull(0)?.className ?: javaClass.name
    return LoggerFactory.getLogger(classToUseForLogMessage)
  }

  /**
   * Returns an error in the server's documented JSON error format unless the request only accepts
   * text/plain responses, in which case it just returns the error message. This is needed to work
   * with rhizo-client which only accepts text/plain responses.
   */
  private fun simpleErrorResponse(
      message: String,
      status: HttpStatus,
      request: WebRequest
  ): ResponseEntity<*> {
    return if (request.getHeader("Accept") == "text/plain") {
      ResponseEntity(message, status)
    } else {
      ResponseEntity(SimpleErrorResponsePayload(ErrorDetails(message = message)), status)
    }
  }
}
