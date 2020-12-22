package com.terraformation.seedbank.api

import io.micronaut.http.HttpStatus
import io.micronaut.http.exceptions.HttpStatusException

// Controller methods can throw these exceptions to return HTTP error statuses with a consistent
// set of error messages.

class NoOrganizationException :
    HttpStatusException(HttpStatus.FORBIDDEN, "Client is not associated with an organization")

class NotFoundException : HttpStatusException(HttpStatus.NOT_FOUND, "Resource not found")

class WrongOrganizationException :
  HttpStatusException(HttpStatus.FORBIDDEN, "Resource is owned by a different organization")
