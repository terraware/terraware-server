package com.terraformation.backend.db

import com.zaxxer.hikari.HikariDataSource
import java.net.URI
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.util.StringUtils
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest

/**
 * Data source that authenticates to the database using a temporary token rather than a password.
 * The token is generated dynamically by AWS.
 *
 * This is only used if the `aws` profile is active (that is, if the server is launched with
 * `SPRING_PROFILES_ACTIVE=aws`.) Otherwise, the server authenticates with a password as usual.
 */
class IamDataSource : HikariDataSource() {
  private val rdsUtilities = RdsClient.create().utilities()

  override fun getPassword(): String {
    // A JDBC URL looks like jdbc:postgresql://host:port/database, which technically means
    // the host:port part is just a bunch of opaque scheme-specific text. Remove the "jdbc: prefix
    // so that the URL parser can pick it apart.
    val uri = URI(jdbcUrl.substringAfter("jdbc:"))

    val tokenRequest =
        GenerateAuthenticationTokenRequest.builder()
            .hostname(uri.host)
            .port(uri.port)
            .username(username)
            .build()

    return rdsUtilities.generateAuthenticationToken(tokenRequest)
  }

  /**
   * Instantiates the data source if needed. This is a copy of
   * [org.springframework.boot.autoconfigure.jdbc.DataSourceConfiguration.Hikari] that instantiates
   * our class instead of the Hikari one.
   */
  @Configuration(proxyBeanMethods = false)
  class IamDataSourceConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Profile("aws")
    fun dataSource(properties: DataSourceProperties): IamDataSource {
      val dataSource =
          properties.initializeDataSourceBuilder().type(IamDataSource::class.java).build()

      if (StringUtils.hasText(properties.name)) {
        dataSource.poolName = properties.name
      }

      return dataSource
    }
  }
}
