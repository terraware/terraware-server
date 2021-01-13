package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.DEVICE
import com.terraformation.seedbank.db.tables.references.SITE
import com.terraformation.seedbank.db.tables.references.SITE_MODULE
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class DeviceFetcher(private val dslContext: DSLContext) {
  fun getOrganizationId(deviceId: Long): Long? {
    return dslContext
        .select(SITE.ORGANIZATION_ID)
        .from(SITE)
        .join(SITE_MODULE)
        .on(SITE.ID.eq(SITE_MODULE.SITE_ID))
        .join(DEVICE)
        .on(SITE_MODULE.ID.eq(DEVICE.SITE_MODULE_ID))
        .where(DEVICE.ID.eq(deviceId))
        .fetchOne(SITE.ORGANIZATION_ID)
  }
}
