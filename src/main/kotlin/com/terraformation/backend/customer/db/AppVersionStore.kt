package com.terraformation.backend.customer.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tables.daos.AppVersionsDao
import com.terraformation.backend.db.tables.pojos.AppVersionsRow
import com.terraformation.backend.db.tables.references.APP_VERSIONS
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class AppVersionStore(
    private val appVersionsDao: AppVersionsDao,
    private val dslContext: DSLContext
) {
  fun findAll(): List<AppVersionsRow> {
    return dslContext
        .selectFrom(APP_VERSIONS)
        .orderBy(APP_VERSIONS.APP_NAME, APP_VERSIONS.PLATFORM)
        .fetchInto(AppVersionsRow::class.java)
  }

  fun create(row: AppVersionsRow) {
    requirePermissions { updateAppVersions() }

    appVersionsDao.insert(row)
  }

  fun update(original: AppVersionsRow, desired: AppVersionsRow) {
    requirePermissions { updateAppVersions() }

    dslContext
        .update(APP_VERSIONS)
        .set(APP_VERSIONS.APP_NAME, desired.appName)
        .set(APP_VERSIONS.PLATFORM, desired.platform)
        .set(APP_VERSIONS.MINIMUM_VERSION, desired.minimumVersion)
        .set(APP_VERSIONS.RECOMMENDED_VERSION, desired.recommendedVersion)
        .where(APP_VERSIONS.APP_NAME.eq(original.appName))
        .and(APP_VERSIONS.PLATFORM.eq(original.platform))
        .execute()
  }

  fun delete(appName: String, platform: String) {
    requirePermissions { updateAppVersions() }

    dslContext
        .deleteFrom(APP_VERSIONS)
        .where(APP_VERSIONS.APP_NAME.eq(appName))
        .and(APP_VERSIONS.PLATFORM.eq(platform))
        .execute()
  }
}
