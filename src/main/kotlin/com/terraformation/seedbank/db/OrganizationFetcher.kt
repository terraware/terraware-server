package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.records.OrganizationRecord
import com.terraformation.seedbank.db.tables.references.ORGANIZATION
import javax.inject.Singleton
import org.jooq.DSLContext

@Singleton
class OrganizationFetcher(private val dslContext: DSLContext) {
  fun getAllOrganizations(): List<OrganizationRecord> {
    return dslContext.selectFrom(ORGANIZATION).fetch()
  }
}
