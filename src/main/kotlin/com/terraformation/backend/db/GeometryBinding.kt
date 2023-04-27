package com.terraformation.backend.db

import java.sql.SQLFeatureNotSupportedException
import org.jooq.Binding
import org.jooq.BindingGetResultSetContext
import org.jooq.BindingGetSQLInputContext
import org.jooq.BindingGetStatementContext
import org.jooq.BindingRegisterContext
import org.jooq.BindingSQLContext
import org.jooq.BindingSetSQLOutputContext
import org.jooq.BindingSetStatementContext
import org.jooq.Converter
import org.jooq.impl.DSL
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.io.WKBReader

/**
 * Alias for jOOQ's Geometry class. The application code uses the [Geometry] class from the JTS
 * library, but jOOQ uses its own class of the same name to represent the raw values of `GEOMETRY`
 * columns. [GeometryBinding] needs to convert between the two; define a typealias to make the code
 * easier to understand.
 */
private typealias JooqGeometry = org.jooq.Geometry

/**
 * jOOQ binding for the JTS Java library's [Geometry] type hierarchy. Allows application code to
 * read and write GEOMETRY columns.
 *
 * Geometry values are always transformed to longitude/latitude (SRID 4326) when they are written to
 * the database. The transformation happens on the database server.
 *
 * Note that [Geometry] is an abstract class; queries will always return instances of a concrete
 * class such as [Point]. It is possible for the same GEOMETRY column on a single table to hold
 * geometries of multiple types, that is, a given query might return a mix of [Point] and [Polygon]
 * and other geometry objects.
 */
class GeometryBinding : Binding<JooqGeometry, Geometry> {
  private val converter = GeometryConverter()

  class GeometryConverter : Converter<JooqGeometry, Geometry> {
    private val wkbReader = WKBReader()

    override fun from(databaseObject: JooqGeometry?): Geometry? {
      // Geometry values are returned in WKB (Well Known Binary) form, encoded in hexadecimal.
      val wkb = databaseObject?.data() ?: return null

      return wkbReader.read(WKBReader.hexToBytes(wkb))
    }

    /**
     * Renders a geometry in WKT (Well Known Text) form with SRID included. PostGIS knows how to
     * cast WKT strings to the GEOMETRY type.
     */
    override fun to(userObject: Geometry?) = userObject?.toJooqGeometry()

    override fun fromType() = org.jooq.Geometry::class.java

    override fun toType() = Geometry::class.java
  }

  override fun converter() = converter

  override fun sql(ctx: BindingSQLContext<Geometry>) {
    ctx.render().visit(DSL.sql("st_transform(?::geometry, ${SRID.LONG_LAT})"))
  }

  override fun register(ctx: BindingRegisterContext<Geometry>) {
    throw UnsupportedOperationException("Callable statements not supported yet")
  }

  override fun set(ctx: BindingSetStatementContext<Geometry>) {
    ctx.statement().setObject(ctx.index(), ctx.convert(converter).value()?.data())
  }

  override fun get(ctx: BindingGetResultSetContext<Geometry>) {
    ctx.convert(converter).value(ctx.resultSet().getObject(ctx.index())?.toJooqGeometry())
  }

  override fun get(ctx: BindingGetStatementContext<Geometry>) {
    ctx.convert(converter).value(ctx.statement().getObject(ctx.index())?.toJooqGeometry())
  }

  override fun set(ctx: BindingSetSQLOutputContext<Geometry>) {
    throw SQLFeatureNotSupportedException("Oracle-specific API does not apply to PostgreSQL")
  }

  override fun get(ctx: BindingGetSQLInputContext<Geometry>) {
    throw SQLFeatureNotSupportedException("Oracle-specific API does not apply to PostgreSQL")
  }

  companion object {
    /** Wraps the string representation of a geometry value in a jOOQ Geometry object. */
    private fun Any.toJooqGeometry(): JooqGeometry {
      val wkt =
          if (this is Geometry) {
            "SRID=$srid;${toText()}"
          } else {
            toString()
          }
      return JooqGeometry.valueOf(wkt)
    }
  }
}
