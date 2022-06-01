package com.terraformation.backend.device.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.terraformation.backend.api.DeviceManagerAppEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.db.DeviceTemplateCategory
import com.terraformation.backend.db.DeviceTemplateId
import com.terraformation.backend.db.tables.daos.DeviceTemplatesDao
import com.terraformation.backend.db.tables.pojos.DeviceTemplatesRow
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@DeviceManagerAppEndpoint
@RequestMapping("/api/v1/devices/templates")
@RestController
class DeviceTemplatesController(
    private val deviceTemplatesDao: DeviceTemplatesDao,
    private val objectMapper: ObjectMapper,
) {
  @GetMapping
  fun listDeviceTemplates(
      @RequestParam category: DeviceTemplateCategory? = null
  ): ListDeviceTemplatesResponsePayload {
    val templates =
        if (category != null) {
          deviceTemplatesDao.fetchByCategoryId(category)
        } else {
          deviceTemplatesDao.findAll()
        }

    val templatePayloads =
        templates.map { DeviceTemplatePayload(it, objectMapper) }.sortedBy { it.name }
    return ListDeviceTemplatesResponsePayload(templatePayloads)
  }
}

data class DeviceTemplatePayload(
    val id: DeviceTemplateId,
    val category: DeviceTemplateCategory,
    val name: String,
    val type: String,
    val make: String,
    val model: String,
    val protocol: String?,
    val address: String?,
    val port: Int?,
    val settings: Map<String, Any?>?,
    val pollingInterval: Int?,
) {
  constructor(
      row: DeviceTemplatesRow,
      objectMapper: ObjectMapper
  ) : this(
      row.id!!,
      row.categoryId!!,
      row.name!!,
      row.deviceType!!,
      row.make!!,
      row.model!!,
      row.protocol,
      row.address,
      row.port,
      row.settings?.let { objectMapper.readValue(it.data()) },
      row.pollingInterval)
}

data class ListDeviceTemplatesResponsePayload(val templates: List<DeviceTemplatePayload>) :
    SuccessResponsePayload
