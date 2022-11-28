package com.terraformation.backend.api

import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response

// Exceptions for HTTP status codes that aren't covered by the standard set of JAX-RS exceptions.

class DuplicateNameException(message: String = "A resource with that name already exists") :
    ClientErrorException(message, Response.Status.CONFLICT)

class ResourceInUseException(message: String = "The resource is currently in use") :
    ClientErrorException(message, Response.Status.CONFLICT)
