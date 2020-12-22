package com.terraformation.seedbank.api

import com.terraformation.seedbank.auth.Role
import com.terraformation.seedbank.auth.organizationId
import com.terraformation.seedbank.auth.roles
import com.terraformation.seedbank.db.tables.records.SequenceRecord
import com.terraformation.seedbank.db.tables.references.DEVICE
import com.terraformation.seedbank.db.tables.references.SEEDBANK_SYSTEM
import com.terraformation.seedbank.db.tables.references.SEQUENCE
import com.terraformation.seedbank.db.tables.references.SITE
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.micronaut.security.rules.SecurityRule
import io.swagger.v3.oas.annotations.media.Schema
import javax.inject.Singleton
import org.jooq.DSLContext

@Controller("/api/v1/device")
@Secured(SecurityRule.IS_AUTHENTICATED)
@Singleton
class DeviceController(private val dslContext: DSLContext) {
  @Get("/{deviceId}")
  fun getDeviceInfo(auth: Authentication, deviceId: Long): String {
    return "TODO $deviceId ${auth.organizationId}"
  }

  @Get("/{deviceId}/sequences")
  fun listSequences(auth: Authentication, deviceId: Long): ListSequencesResponse {
    val organizationId = auth.organizationId
    val deviceOrganizationId =
        dslContext
            .select(SITE.ORGANIZATION_ID)
            .from(SITE)
            .join(SEEDBANK_SYSTEM)
            .on(SITE.ID.eq(SEEDBANK_SYSTEM.SITE_ID))
            .join(DEVICE)
            .on(SEEDBANK_SYSTEM.ID.eq(DEVICE.SEEDBANK_SYSTEM_ID))
            .where(DEVICE.ID.eq(deviceId))
            .fetchOne(SITE.ORGANIZATION_ID)
            ?: throw NotFoundException()

    if (Role.SUPER_ADMIN !in auth.roles || deviceOrganizationId != organizationId) {
      throw WrongOrganizationException()
    }

    with(SEQUENCE) {
      val elements =
          dslContext.selectFrom(SEQUENCE).where(DEVICE_ID.eq(deviceId)).fetch { record ->
            ListSequencesElement(record)
          }
      return ListSequencesResponse(elements)
    }
  }
}

@Schema(requiredProperties = ["id"])
data class ListSequencesElement(val id: Long, val name: String) {
  constructor(record: SequenceRecord) : this(record.id!!, record.name!!)
}

data class ListSequencesResponse(val sequences: List<ListSequencesElement>)
