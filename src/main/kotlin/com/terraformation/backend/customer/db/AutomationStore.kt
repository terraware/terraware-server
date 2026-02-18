package com.terraformation.backend.customer.db

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.AutomationModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.tables.daos.AutomationsDao
import com.terraformation.backend.db.default_schema.tables.pojos.AutomationsRow
import com.terraformation.backend.db.default_schema.tables.references.AUTOMATIONS
import com.terraformation.backend.log.perClassLogger
import jakarta.inject.Named
import java.time.Clock
import org.jooq.DSLContext
import org.jooq.JSONB

@Named
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
      type: String,
      name: String,
      description: String? = null,
      deviceId: DeviceId? = null,
      timeseriesName: String? = null,
      verbosity: Int = 0,
      lowerThreshold: Double? = null,
      upperThreshold: Double? = null,
      settings: JSONB? = null,
  ): AutomationId {
    requirePermissions { createAutomation(facilityId) }

    val row =
        AutomationsRow(
            createdBy = currentUser().userId,
            createdTime = clock.instant(),
            description = description,
            deviceId = deviceId,
            facilityId = facilityId,
            lowerThreshold = lowerThreshold,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = name,
            settings = settings,
            timeseriesName = timeseriesName,
            type = type,
            upperThreshold = upperThreshold,
            verbosity = verbosity,
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

    requirePermissions {
      listAutomations(facilityId)
      readDevice(deviceId)
    }

    return dslContext
        .selectFrom(AUTOMATIONS)
        .where(AUTOMATIONS.DEVICE_ID.eq(deviceId))
        .fetchInto(AutomationsRow::class.java)
        .map { AutomationModel(it, objectMapper) }
  }

  fun update(model: AutomationModel) {
    requirePermissions {
      updateAutomation(model.id)
      model.deviceId?.let { updateDevice(it) }
    }

    val row = automationsDao.fetchOneById(model.id) ?: throw AutomationNotFoundException(model.id)

    if (row.facilityId != model.facilityId) {
      log.warn(
          "Rejecting update of automation ${model.id} with incorrect facility ${model.facilityId}"
      )
      throw AutomationNotFoundException(model.id)
    }

    val modified =
        row.copy(
            description = model.description,
            deviceId = model.deviceId,
            lowerThreshold = model.lowerThreshold,
            modifiedBy = currentUser().userId,
            modifiedTime = clock.instant(),
            name = model.name,
            settings = model.settings,
            timeseriesName = model.timeseriesName,
            type = model.type,
            upperThreshold = model.upperThreshold,
            verbosity = model.verbosity,
        )

    automationsDao.update(modified)
  }

  fun delete(automationId: AutomationId) {
    requirePermissions { deleteAutomation(automationId) }

    automationsDao.deleteById(automationId)
  }
}
