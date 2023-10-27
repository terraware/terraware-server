package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionId
import java.time.Instant
import java.time.LocalDate

data class NewBatchModel(
    val accessionId: AccessionId? = null,
    val addedDate: LocalDate,
    val batchNumber: String? = null,
    val facilityId: FacilityId,
    val germinatingQuantity: Int,
    val notes: String? = null,
    val notReadyQuantity: Int,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val readyQuantity: Int,
    /**
     * Species ID may be null if it will come from elsewhere, such as accession nursery transfer.
     */
    val speciesId: SpeciesId?,
) {
  fun toRow() =
      BatchesRow(
          accessionId = accessionId,
          addedDate = addedDate,
          batchNumber = batchNumber,
          facilityId = facilityId,
          germinatingQuantity = germinatingQuantity,
          latestObservedGerminatingQuantity = germinatingQuantity,
          latestObservedNotReadyQuantity = notReadyQuantity,
          latestObservedReadyQuantity = readyQuantity,
          notes = notes,
          notReadyQuantity = notReadyQuantity,
          projectId = projectId,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          speciesId = speciesId,
      )
}

data class ExistingBatchModel(
    val accessionId: AccessionId? = null,
    val addedDate: LocalDate,
    val batchNumber: String,
    val facilityId: FacilityId,
    val germinatingQuantity: Int,
    val id: BatchId,
    val latestObservedGerminatingQuantity: Int,
    val latestObservedNotReadyQuantity: Int,
    val latestObservedReadyQuantity: Int,
    val latestObservedTime: Instant,
    val notes: String? = null,
    val notReadyQuantity: Int,
    val organizationId: OrganizationId,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val readyQuantity: Int,
    val speciesId: SpeciesId,
    val version: Int,
) {
  constructor(
      row: BatchesRow
  ) : this(
      accessionId = row.accessionId,
      addedDate = row.addedDate!!,
      batchNumber = row.batchNumber!!,
      facilityId = row.facilityId!!,
      germinatingQuantity = row.germinatingQuantity!!,
      id = row.id!!,
      latestObservedGerminatingQuantity = row.latestObservedGerminatingQuantity!!,
      latestObservedNotReadyQuantity = row.latestObservedNotReadyQuantity!!,
      latestObservedReadyQuantity = row.latestObservedReadyQuantity!!,
      latestObservedTime = row.latestObservedTime!!,
      notes = row.notes,
      notReadyQuantity = row.notReadyQuantity!!,
      organizationId = row.organizationId!!,
      projectId = row.projectId,
      readyByDate = row.readyByDate,
      readyQuantity = row.readyQuantity!!,
      speciesId = row.speciesId!!,
      version = row.version!!,
  )
}
