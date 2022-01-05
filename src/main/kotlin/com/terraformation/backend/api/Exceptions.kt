package com.terraformation.backend.api

import javax.ws.rs.ClientErrorException
import javax.ws.rs.core.Response

// Exceptions for HTTP status codes that aren't covered by the standard set of JAX-RS exceptions.

class DuplicateNameException(message: String = "A resource with that name already exists") :
    ClientErrorException(message, Response.Status.CONFLICT)

class NotAuthenticatedException(message: String = "Client is not authenticated") :
    ClientErrorException(message, Response.Status.UNAUTHORIZED)

class ResourceInUseException(message: String = "The resource is currently in use") :
    ClientErrorException(message, Response.Status.CONFLICT)
