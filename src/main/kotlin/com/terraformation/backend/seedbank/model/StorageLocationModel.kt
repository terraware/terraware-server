package com.terraformation.backend.seedbank.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.seedbank.StorageCondition
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.tables.pojos.StorageLocationsRow

data class StorageLocationModel<ID : StorageLocationId?>(
    val condition: StorageCondition? = null,
    val facilityId: FacilityId,
    val id: ID,
    val name: String,
)

typealias NewStorageLocationModel = StorageLocationModel<Nothing?>

typealias ExistingStorageLocationModel = StorageLocationModel<StorageLocationId>

fun StorageLocationsRow.toModel(): ExistingStorageLocationModel =
    ExistingStorageLocationModel(conditionId!!, facilityId!!, id!!, name!!)
