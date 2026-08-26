package com.terraformation.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Configures the application to use [CorrelationLoggingDataSource] as its connection pool. */
@Configuration
class DataSourceConfig {
  /**
   * Returns the pool configuration. Spring will populate this configuration with additional
   * connection pool properties from the application config.
   */
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  fun hikariConfig(properties: DataSourceProperties): HikariConfig {
    return HikariConfig().apply {
      jdbcUrl = properties.determineUrl()
      username = properties.determineUsername()
      password = properties.determinePassword()
      driverClassName = properties.determineDriverClassName()
    }
  }

  @Bean
  fun dataSource(hikariConfig: HikariConfig): DataSource {
    if ("postgres" in hikariConfig.jdbcUrl) {
      return CorrelationLoggingDataSource(hikariConfig)
    } else {
      return HikariDataSource(hikariConfig)
    }
  }
}
