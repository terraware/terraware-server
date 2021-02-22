package com.terraformation.seedbank.config

import com.terraformation.seedbank.auth.ApiKeyAuthenticationProvider
import com.terraformation.seedbank.db.tables.daos.ApiKeyDao
import com.terraformation.seedbank.db.tables.pojos.ApiKey
import com.terraformation.seedbank.db.tables.references.API_KEY
import com.terraformation.seedbank.db.tables.references.SITE_MODULE
import com.terraformation.seedbank.services.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

@ManagedBean
class DatabaseBootstrapper(
    private val apiKeyAuthenticationProvider: ApiKeyAuthenticationProvider,
    private val apiKeyDao: ApiKeyDao,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext
) {
  private val log = perClassLogger()

  @EventListener
  fun onApplicationStart(event: ApplicationStartedEvent) {
    updateApiKey()
  }

  private fun updateApiKey() {
    val apiKey = config.apiKey ?: return
    val hashedKey = apiKeyAuthenticationProvider.hashApiKey(apiKey)

    if (apiKeyDao.fetchOneByHash(hashedKey) != null) {
      return
    }

    val organizationId =
        dslContext
            .select(SITE_MODULE.site().ORGANIZATION_ID)
            .from(SITE_MODULE)
            .where(SITE_MODULE.ID.eq(config.siteModuleId))
            .fetchOne(SITE_MODULE.site().ORGANIZATION_ID)
    if (organizationId == null) {
      log.error("Unable to determine organization ID for site module ${config.siteModuleId}")
      return
    }

    dslContext.transaction { _ ->
      dslContext.deleteFrom(API_KEY).execute()
      apiKeyDao.insert(
          ApiKey(
              hash = hashedKey,
              keyPart = apiKey.substring(0, 2) + ".." + apiKey.substring(apiKey.length - 2),
              organizationId = organizationId,
              createdTime = clock.instant()))
    }

    log.info("Updated API key table with key from terraware.api-key config option")
  }
}
