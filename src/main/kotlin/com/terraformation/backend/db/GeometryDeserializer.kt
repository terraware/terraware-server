package com.terraformation.backend.db

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.PrecisionModel
import org.locationtech.jts.io.ParseException
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.operation.valid.IsValidOp
import tools.jackson.core.JsonParser
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer

/** Deserializes GeoJSON strings into JTS [Geometry] objects of the appropriate types. */
class GeometryDeserializer : ValueDeserializer<Geometry>() {
  private val geoJsonReader =
      GeoJsonReader(GeometryFactory(PrecisionModel(PrecisionModel.FLOATING), SRID.LONG_LAT))

  override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Geometry {
    return readGeometry(jp, jp.readValueAsTree(), null)
  }

  private fun readGeometry(jp: JsonParser, tree: JsonNode, expectedType: String?): Geometry {
    val type =
        tree.findValuesAsString("type").getOrNull(0)
            ?: throw StreamReadException(jp, "Missing geometry type", jp.currentLocation())
    if (expectedType != null && expectedType != type) {
      throw StreamReadException(jp, "Expected type $expectedType, was $type")
    }

    val geometry =
        try {
          geoJsonReader.read(tree.toString())
        } catch (e: ParseException) {
          throw StreamReadException(jp, e.message, e)
        }

    val validator = IsValidOp(geometry)
    val error = validator.validationError
    if (error != null) {
      throw StreamReadException(jp, error.message)
    }

    if (geometry.isEmpty) {
      throw StreamReadException(jp, "$type has no coordinates")
    }

    return geometry
  }

  inner class SubclassDeserializer<T : Geometry>(private val subclass: Class<T>) :
      ValueDeserializer<T>() {
    private val expectedType = subclass.simpleName

    override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): T {
      val geom = readGeometry(jp, jp.readValueAsTree(), expectedType)

      if (subclass.isInstance(geom)) {
        @Suppress("UNCHECKED_CAST")
        return geom as T
      } else {
        throw StreamReadException(
            jp,
            "Expected geometry type ${subclass.simpleName}, was ${geom.geometryType}",
            jp.currentLocation(),
        )
      }
    }
  }

  /** Returns a deserializer for a specific geometry type. */
  inline fun <reified T : Geometry> forSubclass(
      subclass: Class<T> = T::class.java
  ): ValueDeserializer<T> {
    return SubclassDeserializer(subclass)
  }
}
