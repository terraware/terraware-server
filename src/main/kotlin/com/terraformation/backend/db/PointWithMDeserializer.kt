package com.terraformation.backend.db

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.CoordinateXYZM
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel
import tools.jackson.core.JsonParser
import tools.jackson.core.exc.StreamReadException
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer

class PointWithMDeserializer : ValueDeserializer<Point>() {
  private val geometryFactory =
      GeometryFactory(PrecisionModel(PrecisionModel.FLOATING), SRID.CARTESIAN)

  override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Point {
    val tree = jp.readValueAsTree<JsonNode>()

    val type =
        tree["type"]?.stringValue()
            ?: throw StreamReadException(jp, "Missing geometry type", jp.currentLocation())
    if (type != "Point") {
      throw StreamReadException(jp, "Expected type Point, was $type", jp.currentLocation())
    }

    val coords =
        tree["coordinates"]
            ?: throw StreamReadException(jp, "Point has no coordinates", jp.currentLocation())
    if (coords.size() < 2) {
      throw StreamReadException(jp, "Point has no coordinates", jp.currentLocation())
    }

    val x = coords[0].doubleValue()
    val y = coords[1].doubleValue()
    val coordinate =
        when {
          coords.size() >= 4 ->
              CoordinateXYZM(x, y, coords[2].doubleValue(), coords[3].doubleValue())
          coords.size() == 3 -> Coordinate(x, y, coords[2].doubleValue())
          else -> CoordinateXY(x, y)
        }

    return geometryFactory.createPoint(coordinate)
  }
}
