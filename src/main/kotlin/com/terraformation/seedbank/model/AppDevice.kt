package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.tables.records.AppDevicesRecord

data class AppDeviceModel(
    val id: Long? = null,
    val appBuild: String? = null,
    val appName: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val name: String? = null,
    val osType: String? = null,
    val osVersion: String? = null,
    val uniqueId: String? = null
) {
  constructor(
      record: AppDevicesRecord
  ) : this(
      record.id,
      record.appBuild,
      record.appName,
      record.brand,
      record.model,
      record.name,
      record.osType,
      record.osVersion,
      record.uniqueId)

  fun nullIfEmpty(): AppDeviceModel? =
      if (listOfNotNull(appBuild, appName, brand, model, name, osType, osVersion, uniqueId)
          .isNotEmpty()) {
        this
      } else {
        null
      }
}
