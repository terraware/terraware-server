package com.terraformation.seedbank.db

import com.terraformation.seedbank.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATION
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class StorageLocationFetcher(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext
) {
  fun fetchStorageConditionsByLocationName(): Map<String, StorageCondition> {
    return with(STORAGE_LOCATION) {
      dslContext
          .select(NAME, CONDITION_ID)
          .from(STORAGE_LOCATION)
          .where(SITE_MODULE_ID.eq(config.siteModuleId))
          .fetch { it.value1()!! to it.value2()!! }
          .toMap()
    }
  }
}
