package com.terraformation.backend.admin

import com.terraformation.backend.db.GeometryBinding
import jakarta.inject.Named
import java.sql.Connection
import java.sql.ResultSetMetaData
import org.jooq.DSLContext
import org.locationtech.jts.geom.Geometry
import org.postgresql.util.PGobject

/**
 * Runs an arbitrary read-only SQL query and turns its geometry column into map features.
 *
 * The query is executed with plain JDBC because the community edition of jOOQ can't convert PostGIS
 * `GEOMETRY` values in ad-hoc query results.
 */
@Named
class SqlMapQueryService(private val dslContext: DSLContext) {
  fun runQuery(sql: String): SqlMapResult {
    validateQuery(sql)

    val rawResult = dslContext.transactionResult { config ->
      config.dsl().connectionResult { connection -> readRows(connection, sql) }
    }

    return buildResult(rawResult)
  }

  private fun validateQuery(sql: String) {
    val parsed = dslContext.parser().parse(sql)
    when (parsed.queries().size) {
      0 -> throw IllegalArgumentException("No SQL query found")
      1 -> Unit
      else -> throw IllegalArgumentException("Cannot include multiple SQL statements in query")
    }
  }

  private fun readRows(connection: Connection, sql: String): RawQueryResult {
    connection.createStatement().use { statement ->
      statement.execute("set transaction read only")
      statement.execute("set local statement_timeout = $STATEMENT_TIMEOUT_MS")
    }

    return connection.prepareStatement(sql).use { statement ->
      statement.maxRows = ROW_LIMIT

      statement.executeQuery().use { resultSet ->
        val metaData = resultSet.metaData
        val columnCount = metaData.columnCount
        val columnNames = (1..columnCount).map { metaData.getColumnLabel(it) }
        val rows = buildList {
          while (resultSet.next()) {
            add((1..columnCount).map { resultSet.getObject(it) })
          }
        }

        RawQueryResult(columnNames, resultSet.metaData, rows)
      }
    }
  }

  private fun buildResult(rawResult: RawQueryResult): SqlMapResult {
    val columnNames = rawResult.columnNames

    val geometryColumn =
        columnNames.indices.firstOrNull { columnIndex ->
          rawResult.metaData.getColumnTypeName(columnIndex + 1) == "geometry"
        } ?: throw IllegalArgumentException("Query result has no geometry column.")

    val rows = rawResult.rows

    if (rows.isEmpty()) {
      return SqlMapResult(
          featureCollection = SqlMapFeatureCollection(emptyList()),
          rowCount = 0,
          skippedNullGeometryCount = 0,
          rowLimitReached = false,
      )
    }

    val labelColumns = columnNames.indices.filter { it != geometryColumn }

    var skippedNullGeometryCount = 0
    val features = rows.mapNotNull { row ->
      val geometry = toGeometry(row[geometryColumn])
      if (geometry == null) {
        skippedNullGeometryCount++
        null
      } else {
        SqlMapFeature(
            geometry,
            labelColumns.associate { columnNames[it] to row[it]?.toString() },
        )
      }
    }

    return SqlMapResult(
        featureCollection = SqlMapFeatureCollection(features),
        rowCount = features.size,
        skippedNullGeometryCount = skippedNullGeometryCount,
        rowLimitReached = rows.size == ROW_LIMIT,
    )
  }

  /**
   * Converts a raw JDBC column value to a JTS [Geometry] if it is a PostGIS geometry, or returns
   * null otherwise. PostGIS geometry columns are returned by the JDBC driver as [PGobject]s whose
   * value is the geometry in hex-encoded WKB form.
   */
  private fun toGeometry(value: Any?): Geometry? =
      if (value is PGobject && value.type == "geometry") {
        val hexValue: String? = value.getValue()
        hexValue?.let { GeometryBinding.geometryFromWkbHex(it) }
      } else {
        null
      }

  private class RawQueryResult(
      val columnNames: List<String>,
      val metaData: ResultSetMetaData,
      val rows: List<List<Any?>>,
  )

  data class SqlMapFeature(
      val geometry: Geometry,
      val properties: Map<String, String?>,
  ) {
    val type
      get() = "Feature"
  }

  data class SqlMapFeatureCollection(val features: List<SqlMapFeature>) {
    val type
      get() = "FeatureCollection"
  }

  /**
   * Result of running a SQL map query.
   *
   * @param featureCollection GeoJSON FeatureCollection.
   * @param rowCount Number of features in the collection (rows with a non-null geometry).
   * @param skippedNullGeometryCount Number of result rows whose geometry value was null.
   * @param rowLimitReached True if the query hit the [ROW_LIMIT] cap.
   */
  data class SqlMapResult(
      val featureCollection: SqlMapFeatureCollection,
      val rowCount: Int,
      val skippedNullGeometryCount: Int,
      val rowLimitReached: Boolean,
  )

  companion object {
    const val ROW_LIMIT = 10_000
    const val STATEMENT_TIMEOUT_MS = 30_000
  }
}
