package com.terraformation.backend.gis.db

import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.PlantObservationId
import com.terraformation.backend.db.PlantObservationNotFoundException
import com.terraformation.backend.db.tables.daos.PlantObservationsDao
import com.terraformation.backend.db.tables.daos.PlantsDao
import com.terraformation.backend.db.tables.pojos.PlantObservationsRow
import java.lang.IllegalArgumentException
import java.time.Clock
import javax.annotation.ManagedBean

@ManagedBean
class PlantObservationsStore(
    private val clock: Clock,
    private val plantsDao: PlantsDao,
    private val observDao: PlantObservationsDao,
) {
  fun create(row: PlantObservationsRow): PlantObservationsRow {
    val featureId = row.featureId ?: throw IllegalArgumentException("featureId cannot be null")

    requirePermissions { createLayerData(featureId) }

    if (plantsDao.fetchOneByFeatureId(featureId) == null) {
      throw IllegalArgumentException(
          "Cannot create Plant Observation because " +
              "there is no Plant associated with feature id $featureId")
    }

    val currTime = clock.instant()
    val result = row.copy(createdTime = currTime, modifiedTime = currTime)
    observDao.insert(result)
    return result
  }

  fun fetch(id: PlantObservationId): PlantObservationsRow? {
    val row = observDao.fetchOneById(id)

    if (row == null || !currentUser().canReadLayerData(row.featureId!!)) {
      return null
    }

    return row
  }

  fun fetchList(featureId: FeatureId): List<PlantObservationsRow> {
    if (!currentUser().canReadLayerData(featureId)) {
      return emptyList()
    }
    return observDao.fetchByFeatureId(featureId)
  }

  fun update(row: PlantObservationsRow): PlantObservationsRow {
    val id = row.id ?: throw IllegalArgumentException("Plant observation id cannot be null")

    val existingRow = observDao.fetchOneById(id)

    if (existingRow == null || !currentUser().canUpdateLayerData(existingRow.featureId!!)) {
      throw PlantObservationNotFoundException(id)
    }

    if (row.featureId != null && row.featureId != existingRow.featureId) {
      throw IllegalArgumentException(
          "Cannot associate the plant observation with a different feature")
    }

    if (existingRow.copy(featureId = null, createdTime = null, modifiedTime = null) ==
        row.copy(featureId = null, createdTime = null, modifiedTime = null)) {
      return existingRow
    }

    val result =
        row.copy(
            featureId = existingRow.featureId,
            createdTime = existingRow.createdTime,
            modifiedTime = clock.instant())
    observDao.update(result)
    return result
  }
}
