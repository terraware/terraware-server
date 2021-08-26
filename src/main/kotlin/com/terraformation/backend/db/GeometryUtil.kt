package com.terraformation.backend.db

import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.Point
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Converts a GEOMETRY column value to a GeoJSON string on the database server.
 *
 * In general, it shouldn't be necessary to use this; the PostGIS Java library's Geometry classes
 * such as [Point] will automatically be rendered as GeoJSON if they're included in payloads that
 * are returned to the client.
 *
 * The `ST_AsGeoJSON` function, while not a part of the OpenGIS SQL standard, is supported by
 * multiple database engines; it isn't PostGIS-specific.
 */
fun Field<Geometry?>.asGeoJson(): Field<String?> =
    DSL.function("ST_AsGeoJSON", String::class.java, this)

/**
 * Returns a SQL function call to transform a GEOMETRY column value to a specific coordinate system
 * (SRID).
 */
fun Field<Geometry?>.transformSrid(srid: Int): Field<Geometry?> =
    DSL.function("ST_Transform", Geometry::class.java, this, DSL.`val`(srid))
