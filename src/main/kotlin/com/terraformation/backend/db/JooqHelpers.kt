package com.terraformation.backend.db

import org.jooq.Field
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point

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
 * Wraps a [Geometry] field for use in a multiset query. Workaround for
 * https://github.com/jOOQ/jOOQ/issues/14195.
 */
fun Field<Geometry?>.forMultiset(): Field<Geometry?> =
    DSL.field("substring(ST_AsEWKB(?)::text, 3)", GeometryBinding.dataType, this)

/**
 * Returns a dummy multiset that evaluates to an empty list but doesn't actually query anything.
 * This can be used when you want to conditionally include a multiset in a query, like:
 *
 *     val multisetField = if (shouldIncludeRealSubquery) {
 *       DSL.multiset([real subquery goes here])
 *     } else {
 *       emptyMultiset()
 *     }
 *
 * Then you can include `multisetField` in your select list and the resulting jOOQ query will have
 * the same type signature whether the query has the real multiset or not.
 */
fun <T> emptyMultiset(): Field<List<T>> = DSL.multiset(DSL.selectOne()).convertFrom { emptyList() }
