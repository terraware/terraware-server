package com.terraformation.backend.customer.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.tables.daos.AutomationsDao
import com.terraformation.backend.db.tables.pojos.AutomationsRow
import com.terraformation.backend.db.tables.references.AUTOMATIONS
import com.terraformation.backend.log.perClassLogger
import java.time.Clock
import javax.annotation.ManagedBean
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL

@ManagedBean
class AutomationStore(
    private val automationsDao: AutomationsDao,
    private val clock: Clock,
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
    private val parentStore: ParentStore,
) {
  private val log = perClassLogger()

  fun create(
      facilityId: FacilityId,
      name: String,
      description: String?,
      configuration: Map<String, Any?>?
  ): AutomationId {
    requirePermissions { createAutomation(facilityId) }

    val row =
        AutomationsRow(
            configuration = toJsonb(configuration),
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = description,
            facilityId = facilityId,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
        )

    automationsDao.insert(row)

    return row.id!!
  }

  fun fetchByFacilityId(facilityId: FacilityId): List<AutomationModel> {
    requirePermissions { listAutomations(facilityId) }

    return automationsDao.fetchByFacilityId(facilityId).map { AutomationModel(it, objectMapper) }
  }

  fun fetchOneById(automationId: AutomationId): AutomationModel {
    requirePermissions { readAutomation(automationId) }

    return automationsDao.fetchOneById(automationId)?.let { AutomationModel(it, objectMapper) }
        ?: throw AutomationNotFoundException(automationId)
  }

  fun fetchByDeviceId(deviceId: DeviceId): List<AutomationModel> {
    val facilityId = parentStore.getFacilityId(deviceId) ?: throw DeviceNotFoundException(deviceId)

    requirePermissions { listAutomations(facilityId) }

    return dslContext
        .selectFrom(AUTOMATIONS)
        .where(AUTOMATIONS.FACILITY_ID.eq(facilityId))
        .and(configurationField(AutomationModel.DEVICE_ID_KEY, DeviceId::class.java).eq(deviceId))
        .fetchInto(AutomationsRow::class.java)
        .map { AutomationModel(it, objectMapper) }
  }

  fun update(model: AutomationModel) {
    requirePermissions { updateAutomation(model.id) }

    val row = automationsDao.fetchOneById(model.id) ?: throw AutomationNotFoundException(model.id)

    if (row.facilityId != model.facilityId) {
      log.warn(
          "Rejecting update of automation ${model.id} with incorrect facility ${model.facilityId}")
      throw AutomationNotFoundException(model.id)
    }

    val modified =
        row.copy(
            configuration = toJsonb(model.configuration),
            description = model.description,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = model.name,
        )

    automationsDao.update(modified)
  }

  fun delete(automationId: AutomationId) {
    requirePermissions { deleteAutomation(automationId) }

    automationsDao.deleteById(automationId)
  }

  private fun toJsonb(value: Map<String, Any?>?): JSONB? {
    return if (value == null) {
      null
    } else {
      JSONB.jsonb(objectMapper.writeValueAsString(value))
    }
  }

  private fun <T> configurationField(name: String, type: Class<T>): Field<T?> =
      DSL.jsonbValue(AUTOMATIONS.CONFIGURATION, "\$.\"$name\"").cast(type)
}
