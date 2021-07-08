package com.terraformation.seedbank.db

import com.terraformation.seedbank.config.TerrawareServerConfig
import com.terraformation.seedbank.db.tables.references.STORAGE_LOCATIONS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class StorageLocationStore(
    private val config: TerrawareServerConfig,
    private val dslContext: DSLContext
) {
  fun fetchStorageConditionsByLocationName(): Map<String, StorageCondition> {
    return with(STORAGE_LOCATIONS) {
      dslContext
          .select(NAME, CONDITION_ID)
          .from(STORAGE_LOCATIONS)
          .where(FACILITY_ID.eq(config.facilityId))
          .orderBy(NAME)
          .fetch { it.value1()!! to it.value2()!! }
          .toMap()
    }
  }
}
