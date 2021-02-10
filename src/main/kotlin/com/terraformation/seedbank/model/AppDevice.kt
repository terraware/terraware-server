package com.terraformation.seedbank.model

import com.terraformation.seedbank.db.tables.records.AppDeviceRecord

interface AppDeviceFields {
  val appBuild: String?
  val appName: String?
  val brand: String?
  val model: String?
  val name: String?
  val osType: String?
  val osVersion: String?
  val uniqueId: String?

  fun nullIfEmpty(): AppDeviceFields? =
      if (listOfNotNull(appBuild, appName, brand, model, name, osType, osVersion, uniqueId)
          .isNotEmpty()) {
        this
      } else {
        null
      }
}

data class AppDeviceModel(
    val id: Long,
    override val appBuild: String?,
    override val appName: String?,
    override val brand: String?,
    override val model: String?,
    override val name: String?,
    override val osType: String?,
    override val osVersion: String?,
    override val uniqueId: String?
) : AppDeviceFields {
  constructor(
      record: AppDeviceRecord
  ) : this(
      record.id!!,
      record.appBuild,
      record.appName,
      record.brand,
      record.model,
      record.name,
      record.osType,
      record.osVersion,
      record.uniqueId)
}
