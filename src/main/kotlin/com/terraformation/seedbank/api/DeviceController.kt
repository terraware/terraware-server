package com.terraformation.seedbank.api

import com.terraformation.seedbank.auth.ClientIdentity
import com.terraformation.seedbank.auth.Role
import com.terraformation.seedbank.db.DeviceFetcher
import com.terraformation.seedbank.db.tables.daos.TimeseriesDao
import com.terraformation.seedbank.db.tables.pojos.Timeseries
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/device")
@Hidden // Hide from Swagger docs while iterating on the seed bank app's API
@Tag(name = "AdminApp")
class DeviceController(
    private val deviceFetcher: DeviceFetcher,
    private val timeseriesDao: TimeseriesDao
) {
  @GetMapping("/{deviceId}")
  fun getDeviceInfo(
      @AuthenticationPrincipal auth: ClientIdentity,
      @PathVariable deviceId: Long
  ): String {
    return "TODO $deviceId ${auth.organizationId}"
  }

  @GetMapping("/{deviceId}/sequences")
  fun listSequences(
      @AuthenticationPrincipal auth: ClientIdentity,
      @PathVariable deviceId: Long
  ): ListSequencesResponse {
    val organizationId = auth.organizationId
    val deviceOrganizationId =
        deviceFetcher.getOrganizationId(deviceId) ?: throw NotFoundException()

    if (Role.SUPER_ADMIN !in auth.roles || deviceOrganizationId != organizationId) {
      throw WrongOrganizationException()
    }

    val elements = timeseriesDao.fetchByDeviceId(deviceId).map { ListSequencesElement(it) }
    return ListSequencesResponse(elements)
  }
}

@Schema(requiredProperties = ["id"])
data class ListSequencesElement(val id: Long, val name: String) {
  constructor(pojo: Timeseries) : this(pojo.id!!, pojo.name!!)
}

data class ListSequencesResponse(val sequences: List<ListSequencesElement>)
