package com.terraformation.backend.db

import java.sql.SQLFeatureNotSupportedException
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryBuilder
import net.postgis.jdbc.geometry.Point
import net.postgis.jdbc.geometry.Polygon
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

/**
 * jOOQ binding for the PostGIS Java library's [Geometry] type hierarchy. Allows application code to
 * read and write GEOMETRY columns.
 *
 * Geometry values are always transformed to the pseudo-Mercator coordinate system (SRID 3857) when
 * they are written to the database. The transformation happens on the database server.
 *
 * Note that [Geometry] is an abstract class; queries will always return instances of a concrete
 * class such as [Point]. It is possible for the same GEOMETRY column on a single table to hold
 * geometries of multiple types, that is, a given query might return a mix of [Point] and [Polygon]
 * and other geometry objects.
 */
class GeometryBinding : Binding<Any, Geometry> {
  private val converter = GeometryConverter()

  class GeometryConverter : Converter<Any, Geometry> {
    override fun from(databaseObject: Any?): Geometry? {
      return databaseObject?.let { GeometryBuilder.geomFromString("$it") }
    }

    /**
     * Renders a geometry in WKT (Well Known Text) form with SRID included. PostGIS knows how to
     * cast WKT strings to the GEOMETRY type.
     */
    override fun to(userObject: Geometry?) = userObject?.toString()

    override fun fromType() = Any::class.java

    override fun toType() = Geometry::class.java
  }

  override fun converter() = converter

  override fun sql(ctx: BindingSQLContext<Geometry>) {
    ctx.render().visit(DSL.sql("st_transform(?::geometry, ${SRID.SPHERICAL_MERCATOR})"))
  }

  override fun register(ctx: BindingRegisterContext<Geometry>) {
    throw UnsupportedOperationException("Callable statements not supported yet")
  }

  override fun set(ctx: BindingSetStatementContext<Geometry>) {
    ctx.statement().setObject(ctx.index(), ctx.convert(converter).value())
  }

  override fun get(ctx: BindingGetResultSetContext<Geometry>) {
    ctx.convert(converter).value(ctx.resultSet().getObject(ctx.index()))
  }

  override fun get(ctx: BindingGetStatementContext<Geometry>) {
    ctx.convert(converter).value(ctx.statement().getObject(ctx.index()))
  }

  override fun set(ctx: BindingSetSQLOutputContext<Geometry>) {
    throw SQLFeatureNotSupportedException("Oracle-specific API does not apply to PostgreSQL")
  }

  override fun get(ctx: BindingGetSQLInputContext<Geometry>) {
    throw SQLFeatureNotSupportedException("Oracle-specific API does not apply to PostgreSQL")
  }
}
