package com.terraformation.backend.api

import org.springframework.http.HttpStatus

// Controller methods can throw these exceptions to return HTTP error statuses with a consistent
// set of error messages.

abstract class ClientFacingException(val status: HttpStatus, message: String) :
    RuntimeException(message) {
  override val message
    get() = super.message!!
}

class DuplicateNameException(message: String = "A resource with that name already exists") :
    ClientFacingException(HttpStatus.CONFLICT, message)

class InternalErrorException(message: String = "An internal error has occurred") :
    ClientFacingException(HttpStatus.INTERNAL_SERVER_ERROR, message)

class NoOrganizationException(message: String = "Client is not associated with an organization") :
    ClientFacingException(HttpStatus.FORBIDDEN, message)

class NotAuthenticatedException(message: String = "Client is not authenticated") :
    ClientFacingException(HttpStatus.UNAUTHORIZED, message)

class NotFoundException(message: String = "Resource not found") :
    ClientFacingException(HttpStatus.NOT_FOUND, message)

class UnsupportedPhotoFormatException(message: String = "Photos must be of type image/jpeg") :
    ClientFacingException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message)

class WrongOrganizationException(
    message: String = "Resource is owned by a different organization"
) : ClientFacingException(HttpStatus.FORBIDDEN, message)
