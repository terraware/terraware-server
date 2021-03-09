package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.rhizo.DeviceConfig
import com.terraformation.seedbank.db.tables.references.DEVICE
import com.terraformation.seedbank.db.tables.references.ORGANIZATION
import com.terraformation.seedbank.db.tables.references.SITE
import com.terraformation.seedbank.db.tables.references.SITE_MODULE
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.SelectConditionStep
import org.jooq.impl.DSL

@ManagedBean
class DeviceFetcher(private val dslContext: DSLContext) {
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
        .and(SITE.ENABLED.isTrue)
        .and(SITE_MODULE.NAME.eq(siteModuleName))
        .and(SITE_MODULE.ENABLED.isTrue)
        .and(DEVICE.NAME.eq(deviceName))
        .and(DEVICE.ENABLED.isTrue)
  }

  fun fetchDeviceConfigurationForSite(siteModuleId: Long): List<DeviceConfig> {
    return with(DEVICE) {
      dslContext
          .select(
              SITE_MODULE.NAME,
              ID,
              NAME,
              DEVICE_TYPE,
              MAKE,
              MODEL,
              PROTOCOL,
              ADDRESS,
              PORT,
              SETTINGS,
              POLLING_INTERVAL)
          .from(DEVICE)
          .join(SITE_MODULE)
          .on(DEVICE.SITE_MODULE_ID.eq(SITE_MODULE.ID))
          .where(
              SITE_MODULE.SITE_ID.eq(
                  DSL.select(SITE_MODULE.SITE_ID)
                      .from(SITE_MODULE)
                      .where(SITE_MODULE.ID.eq(siteModuleId))))
          .and(DEVICE.ENABLED.isTrue)
          .and(SITE_MODULE.ENABLED.isTrue)
          .orderBy(NAME)
          .fetch { record ->
            DeviceConfig(
                record[SITE_MODULE.NAME]!!,
                record[NAME]!!,
                record[DEVICE_TYPE]!!,
                record[MAKE]!!,
                record[MODEL]!!,
                record[PROTOCOL],
                record[ADDRESS],
                record[PORT],
                record[SETTINGS],
                record[POLLING_INTERVAL])
          }
    }
  }

  fun getDeviceIdByName(siteModuleId: Long, name: String): Long? {
    return dslContext
        .select(DEVICE.ID)
        .from(DEVICE)
        .where(DEVICE.SITE_MODULE_ID.eq(siteModuleId))
        .and(DEVICE.NAME.eq(name))
        .and(DEVICE.ENABLED.isTrue)
        .fetchOne(DEVICE.ID)
  }
}
