package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.DEVICE
import com.terraformation.seedbank.db.tables.references.SEEDBANK_SYSTEM
import com.terraformation.seedbank.db.tables.references.SITE
import javax.inject.Singleton
import org.jooq.DSLContext

@Singleton
class DeviceFetcher(private val dslContext: DSLContext) {
  fun getOrganizationId(deviceId: Long): Int? {
    return dslContext
        .select(SITE.ORGANIZATION_ID)
        .from(SITE)
        .join(SEEDBANK_SYSTEM)
        .on(SITE.ID.eq(SEEDBANK_SYSTEM.SITE_ID))
        .join(DEVICE)
        .on(SEEDBANK_SYSTEM.ID.eq(DEVICE.SEEDBANK_SYSTEM_ID))
        .where(DEVICE.ID.eq(deviceId))
        .fetchOne(SITE.ORGANIZATION_ID)
  }
}
