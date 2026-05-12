package com.terraformation.backend.db

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.CoordinateXY
import org.locationtech.jts.geom.CoordinateXYZM
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.PrecisionModel

class PointWithMDeserializer : JsonDeserializer<Point>() {
  private val geometryFactory =
      GeometryFactory(PrecisionModel(PrecisionModel.FLOATING), SRID.CARTESIAN)

  override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): Point {
    val tree = jp.readValueAsTree<com.fasterxml.jackson.databind.JsonNode>()

    val type =
        tree["type"]?.textValue()
            ?: throw JsonParseException(jp, "Missing geometry type", jp.currentLocation())
    if (type != "Point") {
      throw JsonParseException(jp, "Expected type Point, was $type", jp.currentLocation())
    }

    val coords =
        tree["coordinates"]
            ?: throw JsonParseException(jp, "Point has no coordinates", jp.currentLocation())
    if (coords.size() < 2) {
      throw JsonParseException(jp, "Point has no coordinates", jp.currentLocation())
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
