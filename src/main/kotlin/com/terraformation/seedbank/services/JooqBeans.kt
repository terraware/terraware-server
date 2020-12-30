package com.terraformation.seedbank.services

import com.terraformation.seedbank.db.tables.daos.KeyDao
import com.terraformation.seedbank.db.tables.daos.SequenceDao
import com.terraformation.seedbank.db.tables.daos.SiteDao
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import javax.inject.Inject
import org.jooq.Configuration
import org.jooq.DSLContext

/** Makes generated jOOQ classes available for dependency injection. */
@Factory
class JooqBeans {
  private lateinit var configuration: Configuration

  @Inject
  fun setDslContext(dslContext: DSLContext) {
    configuration = dslContext.configuration()
  }

  @Bean fun keyDao() = KeyDao(configuration)
  @Bean fun sequenceDao() = SequenceDao(configuration)
  @Bean fun siteDao() = SiteDao(configuration)
}
