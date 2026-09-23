package com.terraformation.backend.gis

import io.swagger.v3.oas.annotations.media.Schema
import org.geotools.util.ContentFormatException

@Schema(enumAsRef = true)
enum class GeometryFileErrorCode {
  UnsupportedFormat,
  InvalidFile,
  NoKmlInArchive,
  NoShapefile,
  MultipleShapefiles,
  UnknownCoordinateSystem,
  NoPolygons,
  InvalidGeometry,
  TooManyVertices,
}

/**
 * A content failure with a stable code. Clients translate the code into a message in the user's
 * language; the exception message is for server-side diagnostics only.
 */
class GeometryFileException(val code: GeometryFileErrorCode, cause: Throwable? = null) :
    ContentFormatException(code.name, cause)
