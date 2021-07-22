package com.terraformation.backend.seedbank.db

import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.StorageCondition
import com.terraformation.backend.db.tables.references.STORAGE_LOCATIONS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class StorageLocationStore(private val dslContext: DSLContext) {
  fun fetchStorageConditionsByLocationName(facilityId: FacilityId): Map<String, StorageCondition> {
    return with(STORAGE_LOCATIONS) {
      dslContext
          .select(NAME, CONDITION_ID)
          .from(STORAGE_LOCATIONS)
          .where(FACILITY_ID.eq(facilityId))
          .orderBy(NAME)
          .fetch { it.value1()!! to it.value2()!! }
          .toMap()
    }
  }
}
