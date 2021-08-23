package com.terraformation.backend.db

import java.sql.SQLFeatureNotSupportedException
import net.postgis.jdbc.geometry.Geometry
import net.postgis.jdbc.geometry.GeometryBuilder
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
 * jOOQ binding for the PostGIS Java library's Geometry types. Allows application code to read and
 * write GEOMETRY columns. These will typically be mapped to the [net.postgis.jdbc.geometry.Point]
 * class.
 */
class GeometryBinding : Binding<Any, Geometry> {
  private val converter = PointConverter()

  class PointConverter : Converter<Any, Geometry> {
    override fun from(databaseObject: Any?): Geometry? {
      return databaseObject?.let { GeometryBuilder.geomFromString("$it") }
    }

    override fun to(userObject: Geometry?): Any? {
      return userObject?.let { geom ->
        // Convert the geometry to Well-Known Text (WKT) format, i.e., "POINT(x y z)".
        val sb = StringBuffer()
        geom.outerWKT(sb)
        sb.toString()
      }
    }

    override fun fromType() = Any::class.java

    override fun toType() = Geometry::class.java
  }

  override fun converter() = converter

  override fun sql(ctx: BindingSQLContext<Geometry>) {
    ctx.render().visit(DSL.sql("?::geometry"))
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
