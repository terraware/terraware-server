package com.terraformation.backend.db

import net.postgis.jdbc.geometry.Geometry
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * Returns a SQL function call to transform a GEOMETRY column value to a specific coordinate system
 * (SRID).
 */
fun Field<Geometry?>.transformSrid(srid: Int): Field<Geometry?> =
    DSL.function("ST_Transform", Geometry::class.java, this, DSL.`val`(srid))
