package com.terraformation.seedbank.api

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

// Controller methods can throw these exceptions to return HTTP error statuses with a consistent
// set of error messages.

@ResponseStatus(
    value = HttpStatus.FORBIDDEN,
    reason = "Client is not associated with an organization",
)
class NoOrganizationException : RuntimeException()

@ResponseStatus(value = HttpStatus.UNAUTHORIZED, reason = "Client is not authenticated")
class NotAuthenticatedException : RuntimeException()

@ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "Resource not found")
class NotFoundException : RuntimeException()

@ResponseStatus(
    value = HttpStatus.FORBIDDEN,
    reason = "Resource is owned by a different organization",
)
class WrongOrganizationException : RuntimeException()
