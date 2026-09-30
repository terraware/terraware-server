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
abstract class GeometryFileException(
    val code: GeometryFileErrorCode,
    cause: Throwable? = null,
    message: String? = null,
) : ContentFormatException(if (message != null) "${code.name}: $message" else code.name, cause)

class UnsupportedGeometryFileFormatException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.UnsupportedFormat, cause)

class InvalidGeometryFileException(cause: Throwable? = null, message: String? = null) :
    GeometryFileException(GeometryFileErrorCode.InvalidFile, cause, message)

class NoKmlInArchiveException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.NoKmlInArchive, cause)

class NoShapefileException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.NoShapefile, cause)

class MultipleShapefilesException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.MultipleShapefiles, cause)

class UnknownCoordinateSystemException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.UnknownCoordinateSystem, cause)

class NoPolygonsException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.NoPolygons, cause)

class InvalidGeometryException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.InvalidGeometry, cause)

class TooManyVerticesException(cause: Throwable? = null) :
    GeometryFileException(GeometryFileErrorCode.TooManyVertices, cause)
