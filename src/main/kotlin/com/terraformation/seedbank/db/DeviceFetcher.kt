package com.terraformation.seedbank.db

import com.terraformation.seedbank.db.tables.references.DEVICE
import com.terraformation.seedbank.db.tables.references.ORGANIZATION
import com.terraformation.seedbank.db.tables.references.SITE
import com.terraformation.seedbank.db.tables.references.SITE_MODULE
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.SelectConditionStep

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

  /**
   * Returns the ID of the device named by an MQTT topic.
   *
   * MQTT topics are assumed to follow the format `organization/site/module/device`.
   */
  fun getDeviceIdForMqttTopic(topic: String): Long? {
    val query = queryDeviceIdForMqttTopic(topic)
    return query?.fetchOne(DEVICE.ID)
  }

  fun queryDeviceIdForMqttTopic(topic: String): SelectConditionStep<Record1<Long?>>? {
    val topicParts = topic.split('/', limit = 4)
    if (topicParts.size != 4) {
      return null
    }
    val (orgName, siteName, siteModuleName, deviceName) = topicParts

    return dslContext
        .select(DEVICE.ID)
        .from(ORGANIZATION)
        .join(SITE)
        .on(ORGANIZATION.ID.eq(SITE.ORGANIZATION_ID))
        .join(SITE_MODULE)
        .on(SITE.ID.eq(SITE_MODULE.SITE_ID))
        .join(DEVICE)
        .on(SITE_MODULE.ID.eq(DEVICE.SITE_MODULE_ID))
        .where(ORGANIZATION.NAME.eq(orgName))
        .and(SITE.NAME.eq(siteName))
        .and(SITE_MODULE.NAME.eq(siteModuleName))
        .and(DEVICE.NAME.eq(deviceName))
  }
}
