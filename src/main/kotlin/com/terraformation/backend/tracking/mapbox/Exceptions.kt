package com.terraformation.backend.tracking.mapbox

import io.ktor.http.HttpStatusCode

abstract class MapboxException(message: String) : RuntimeException(message)

class MapboxNotConfiguredException() :
    MapboxException("Server is not configured to make requests to Mapbox")

class MapboxRequestFailedException(message: String) : MapboxException(message) {
  constructor(statusCode: HttpStatusCode) : this("Request failed with HTTP status $statusCode")
}
