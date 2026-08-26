package com.terraformation.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import org.slf4j.MDC

/**
 * DataSource implementation that tells the database which request or job ID is responsible for each
 * database query. This is done using the `application_name` session variable, which is included in
 * the logging configuration on the database side.
 */
class CorrelationLoggingDataSource(config: HikariConfig) : HikariDataSource(config) {
  override fun getConnection(): Connection {
    val conn = super.getConnection()

    try {
      val appName =
          MDC.get("requestId")?.let { "request:$it" }
              ?: MDC.get("jobrunr.jobId")?.let { "job:$it" }
              ?: "terraware-server"

      conn.prepareStatement("SELECT set_config('application_name', ?, false)").use { ps ->
        ps.setString(1, appName)
        ps.execute()
      }

      return conn
    } catch (e: Exception) {
      conn.close()
      throw e
    }
  }
}
