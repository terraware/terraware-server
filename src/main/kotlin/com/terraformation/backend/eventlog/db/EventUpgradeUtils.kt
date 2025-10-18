package com.terraformation.backend.eventlog.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class EventUpgradeUtils(
    val dslContext: DSLContext,
) {
  fun getOrganizationName(organizationId: OrganizationId): String? =
      dslContext.fetchValue(ORGANIZATIONS.NAME, ORGANIZATIONS.ID.eq(organizationId))
}
