package com.terraformation.backend.config

import com.terraformation.backend.auth.ApiKeyAuthenticationProvider
import com.terraformation.backend.db.tables.daos.ApiKeysDao
import com.terraformation.backend.db.tables.pojos.ApiKeysRow
import com.terraformation.backend.db.tables.references.API_KEYS
import com.terraformation.backend.db.tables.references.FACILITIES
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.event.EventListener

@ManagedBean
class DatabaseBootstrapper(
    private val apiKeyAuthenticationProvider: ApiKeyAuthenticationProvider,
    private val apiKeysDao: ApiKeysDao,
    private val clock: Clock,
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext
) {
  private val log = perClassLogger()

  @EventListener
  fun onApplicationStart(@Suppress("UNUSED_PARAMETER") event: ApplicationStartedEvent) {
    updateApiKey()
  }

  fun updateApiKey() {
    val apiKey = config.apiKey ?: return
    val hashedKey = apiKeyAuthenticationProvider.hashApiKey(apiKey)

    if (apiKeysDao.fetchOneByHash(hashedKey) != null) {
      return
    }

    val organizationId =
        dslContext
            .select(FACILITIES.sites().ORGANIZATION_ID)
            .from(FACILITIES)
            .where(FACILITIES.ID.eq(config.facilityId))
            .fetchOne(FACILITIES.sites().ORGANIZATION_ID)
    if (organizationId == null) {
      log.error("Unable to determine organization ID for facility ${config.facilityId}")
      return
    }

    dslContext.transaction { _ ->
      dslContext.deleteFrom(API_KEYS).execute()
      apiKeysDao.insert(
          ApiKeysRow(
              hash = hashedKey,
              keyPart = apiKey.substring(0, 2) + ".." + apiKey.substring(apiKey.length - 2),
              organizationId = organizationId,
              createdTime = clock.instant()))
    }

    log.info("Updated API key table with key from terraware.api-key config option")
  }
}
