package com.terraformation.seedbank.api

import com.terraformation.seedbank.auth.Role
import com.terraformation.seedbank.auth.organizationId
import com.terraformation.seedbank.auth.roles
import com.terraformation.seedbank.db.DeviceFetcher
import com.terraformation.seedbank.db.tables.daos.SequenceDao
import com.terraformation.seedbank.db.tables.pojos.Sequence
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.swagger.v3.oas.annotations.media.Schema
import javax.inject.Singleton

@Controller("/api/v1/device")
@Secured(SecurityRule.IS_AUTHENTICATED)
@Singleton
class DeviceController(
    private val deviceFetcher: DeviceFetcher, private val sequenceDao: SequenceDao
) {
  @Get("/{deviceId}")
  fun getDeviceInfo(auth: Authentication, deviceId: Long): String {
    return "TODO $deviceId ${auth.organizationId}"
  }

  @Get("/{deviceId}/sequences")
  fun listSequences(auth: Authentication, deviceId: Long): ListSequencesResponse {
    val organizationId = auth.organizationId
    val deviceOrganizationId =
        deviceFetcher.getOrganizationId(deviceId) ?: throw NotFoundException()

    if (Role.SUPER_ADMIN !in auth.roles || deviceOrganizationId != organizationId) {
      throw WrongOrganizationException()
    }

    val elements = sequenceDao.fetchByDeviceId(deviceId).map { ListSequencesElement(it) }
    return ListSequencesResponse(elements)
  }
}

@Schema(requiredProperties = ["id"])
data class ListSequencesElement(val id: Long, val name: String) {
  constructor(pojo: Sequence) : this(pojo.id!!, pojo.name!!)
}

data class ListSequencesResponse(val sequences: List<ListSequencesElement>)
