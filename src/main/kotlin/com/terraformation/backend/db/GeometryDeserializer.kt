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

/**
 * Deserializes GeoJSON strings into JTS [Geometry] objects of the appropriate types.
 *
 * @param validateTopology If false, accept geometries with invalid topology such as
 *   self-intersecting rings. Geometries with no coordinates are still rejected unless
 *   [SKIP_VALIDATION] is set.
 */
open class GeometryDeserializer(private val validateTopology: Boolean = true) :
    ValueDeserializer<Geometry>() {
  companion object {
    /**
     * Deserialization context attribute that, when true, accepts geometries with invalid topology
     * or no coordinates. For callers that want to report unusable shapes themselves rather than
     * treat them as parse failures.
     */
    const val SKIP_VALIDATION = "GeometryDeserializer.skipValidation"
  }

  private val geoJsonReader =
      GeoJsonReader(GeometryFactory(PrecisionModel(PrecisionModel.FLOATING), SRID.LONG_LAT))

  override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Geometry {
    return readGeometry(jp, ctxt, jp.readValueAsTree(), null)
  }

  private fun readGeometry(
      jp: JsonParser,
      ctxt: DeserializationContext,
      tree: JsonNode,
      expectedType: String?,
  ): Geometry {
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

    if (ctxt.getAttribute(SKIP_VALIDATION) != true) {
      if (validateTopology) {
        val error = IsValidOp(geometry).validationError
        if (error != null) {
          throw StreamReadException(jp, error.message)
        }
      }

      if (geometry.isEmpty) {
        throw StreamReadException(jp, "$type has no coordinates")
      }
    }

    return geometry
  }

  inner class SubclassDeserializer<T : Geometry>(private val subclass: Class<T>) :
      ValueDeserializer<T>() {
    private val expectedType = subclass.simpleName

    override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): T {
      val geom = readGeometry(jp, ctxt, jp.readValueAsTree(), expectedType)

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

/**
 * Deserializes GeoJSON geometries without checking their topology. For request payload fields whose
 * geometries are reported as validation problems or stored as works in progress rather than
 * rejected as malformed. Use with `@JsonDeserialize(using = ...)`.
 */
class UnvalidatedGeometryDeserializer : GeometryDeserializer(validateTopology = false)
