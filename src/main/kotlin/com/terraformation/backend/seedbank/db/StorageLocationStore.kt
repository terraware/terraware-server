package com.terraformation.backend.seedbank.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.tables.references.STORAGE_LOCATIONS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class StorageLocationStore(private val dslContext: DSLContext) {
  fun fetchStorageConditionsByLocationName(facilityId: FacilityId): Map<String, StorageCondition> {
    return if (currentUser().canReadFacility(facilityId)) {
      with(STORAGE_LOCATIONS) {
        dslContext
            .select(NAME, CONDITION_ID)
            .from(STORAGE_LOCATIONS)
            .where(FACILITY_ID.eq(facilityId))
            .orderBy(NAME)
            .fetch { it.value1()!! to it.value2()!! }
            .toMap()
      }
    } else {
      emptyMap()
    }
  }
}
