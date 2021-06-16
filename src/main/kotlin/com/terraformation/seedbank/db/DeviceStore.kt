package com.terraformation.seedbank.db

import com.terraformation.seedbank.api.rhizo.DeviceConfig
import com.terraformation.seedbank.db.tables.references.DEVICES
import com.terraformation.seedbank.db.tables.references.ORGANIZATIONS
import com.terraformation.seedbank.db.tables.references.SITES
import com.terraformation.seedbank.db.tables.references.SITE_MODULES
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.SelectConditionStep
import org.jooq.impl.DSL

@ManagedBean
class DeviceStore(private val dslContext: DSLContext) {
  /**
   * Returns the ID of the device named by an MQTT topic.
   *
   * MQTT topics are assumed to follow the format `organization/site/module/device`.
   *
   * @throws DeviceNotFoundException The device didn't exist.
   */
  fun getDeviceIdForMqttTopic(topic: String): Long {
    val query = queryDeviceIdForMqttTopic(topic)
    return query?.fetchOne(DEVICES.ID)
        ?: throw DeviceNotFoundException(getErrorForMissingDevice(topic))
  }

  fun queryDeviceIdForMqttTopic(topic: String): SelectConditionStep<Record1<Long?>>? {
    val topicParts = topic.split('/', limit = 4)
    if (topicParts.size != 4) {
      return null
    }
    val (orgName, siteName, siteModuleName, deviceName) = topicParts

    return dslContext
        .select(DEVICES.ID)
        .from(ORGANIZATIONS)
        .join(SITES)
        .on(ORGANIZATIONS.ID.eq(SITES.ORGANIZATION_ID))
        .join(SITE_MODULES)
        .on(SITES.ID.eq(SITE_MODULES.SITE_ID))
        .join(DEVICES)
        .on(SITE_MODULES.ID.eq(DEVICES.SITE_MODULE_ID))
        .where(ORGANIZATIONS.NAME.eq(orgName))
        .and(SITES.NAME.eq(siteName))
        .and(SITES.ENABLED.isTrue)
        .and(SITE_MODULES.NAME.eq(siteModuleName))
        .and(SITE_MODULES.ENABLED.isTrue)
        .and(DEVICES.NAME.eq(deviceName))
        .and(DEVICES.ENABLED.isTrue)
  }

  private fun getErrorForMissingDevice(topic: String): String {
    val topicParts = topic.split('/', limit = 4)
    if (topicParts.size != 4) {
      return "Expected 4 elements in device path, not ${topicParts.size}"
    }
    val (orgName, siteName, siteModuleName, deviceName) = topicParts

    val orgId =
        dslContext
            .select(ORGANIZATIONS.ID)
            .from(ORGANIZATIONS)
            .where(ORGANIZATIONS.NAME.eq(orgName))
            .fetchOne(ORGANIZATIONS.ID)
            ?: return "Organization $orgName not found"
    val siteId =
        dslContext
            .select(SITES.ID)
            .from(SITES)
            .where(SITES.NAME.eq(siteName))
            .and(SITES.ORGANIZATION_ID.eq(orgId))
            .fetchOne(SITES.ID)
            ?: return "Site $siteName not found for organization $orgName"
    val siteModuleId =
        dslContext
            .select(SITE_MODULES.ID)
            .from(SITE_MODULES)
            .where(SITE_MODULES.NAME.eq(siteModuleName))
            .and(SITE_MODULES.SITE_ID.eq(siteId))
            .fetchOne(SITE_MODULES.ID)
            ?: return "Site module $siteModuleName not found for site $siteName"
    val deviceRecord =
        dslContext
            .selectFrom(DEVICES)
            .where(DEVICES.NAME.eq(deviceName))
            .and(DEVICES.SITE_MODULE_ID.eq(siteModuleId))
            .fetchOne()
            ?: return "Device $deviceName not found for site module $siteModuleName"
    if (deviceRecord.enabled != true) {
      return "Device $deviceName is marked as disabled"
    }

    return "Unable to determine why $topic was not found"
  }

  fun fetchDeviceConfigurationForSite(siteModuleId: Long): List<DeviceConfig> {
    return with(DEVICES) {
      dslContext
          .select(
              SITE_MODULES.NAME,
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
          .from(DEVICES)
          .join(SITE_MODULES)
          .on(DEVICES.SITE_MODULE_ID.eq(SITE_MODULES.ID))
          .where(
              SITE_MODULES.SITE_ID.eq(
                  DSL.select(SITE_MODULES.SITE_ID)
                      .from(SITE_MODULES)
                      .where(SITE_MODULES.ID.eq(siteModuleId))))
          .and(DEVICES.ENABLED.isTrue)
          .and(SITE_MODULES.ENABLED.isTrue)
          .orderBy(NAME)
          .fetch { record ->
            DeviceConfig(
                record[SITE_MODULES.NAME]!!,
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
        .select(DEVICES.ID)
        .from(DEVICES)
        .where(DEVICES.SITE_MODULE_ID.eq(siteModuleId))
        .and(DEVICES.NAME.eq(name))
        .and(DEVICES.ENABLED.isTrue)
        .fetchOne(DEVICES.ID)
  }
}
