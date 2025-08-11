package com.terraformation.backend.nursery.model

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchSubstrate
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
    val germinationStartedDate: LocalDate? = null,
    val hardeningOffQuantity: Int,
    val initialBatchId: BatchId? = null,
    val notes: String? = null,
    val notReadyQuantity: Int,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val readyQuantity: Int,
    val seedsSownDate: LocalDate? = null,
    /**
     * Species ID may be null if it will come from elsewhere, such as accession nursery transfer.
     */
    val speciesId: SpeciesId?,
    val subLocationIds: Set<SubLocationId> = emptySet(),
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
) {
  fun toRow() =
      BatchesRow(
          accessionId = accessionId,
          addedDate = addedDate,
          batchNumber = batchNumber,
          facilityId = facilityId,
          germinatingQuantity = germinatingQuantity,
          germinationStartedDate = germinationStartedDate,
          hardeningOffQuantity = hardeningOffQuantity,
          initialBatchId = initialBatchId,
          latestObservedActiveGrowthQuantity = notReadyQuantity,
          latestObservedGerminatingQuantity = germinatingQuantity,
          latestObservedHardeningOffQuantity = hardeningOffQuantity,
          latestObservedReadyQuantity = readyQuantity,
          notes = notes,
          activeGrowthQuantity = notReadyQuantity,
          projectId = projectId,
          readyByDate = readyByDate,
          readyQuantity = readyQuantity,
          seedsSownDate = seedsSownDate,
          speciesId = speciesId,
          substrateId = substrate,
          substrateNotes = substrateNotes,
          treatmentId = treatment,
          treatmentNotes = treatmentNotes,
      )
}

data class ExistingBatchModel(
    val accessionId: AccessionId? = null,
    val accessionNumber: String? = null,
    val addedDate: LocalDate,
    val batchNumber: String,
    val facilityId: FacilityId,
    val germinatingQuantity: Int,
    val germinationRate: Int? = null,
    val germinationStartedDate: LocalDate? = null,
    val hardeningOffQuantity: Int,
    val id: BatchId,
    val initialBatchId: BatchId? = null,
    val latestObservedGerminatingQuantity: Int,
    val latestObservedHardeningOffQuantity: Int,
    val latestObservedNotReadyQuantity: Int,
    val latestObservedReadyQuantity: Int,
    val latestObservedTime: Instant,
    val lossRate: Int? = null,
    val notes: String? = null,
    val notReadyQuantity: Int,
    val organizationId: OrganizationId,
    val projectId: ProjectId? = null,
    val readyByDate: LocalDate? = null,
    val readyQuantity: Int,
    val seedsSownDate: LocalDate? = null,
    val speciesId: SpeciesId,
    val subLocationIds: Set<SubLocationId> = emptySet(),
    val substrate: BatchSubstrate? = null,
    val substrateNotes: String? = null,
    val totalWithdrawn: Int,
    val treatment: SeedTreatment? = null,
    val treatmentNotes: String? = null,
    val version: Int,
) {
  constructor(
      row: BatchesRow,
      subLocationIds: Set<SubLocationId> = emptySet(),
      totalWithdrawn: Int,
      accessionNumber: String? = null,
  ) : this(
      accessionId = row.accessionId,
      accessionNumber = accessionNumber,
      addedDate = row.addedDate!!,
      batchNumber = row.batchNumber!!,
      facilityId = row.facilityId!!,
      germinatingQuantity = row.germinatingQuantity!!,
      germinationRate = row.germinationRate,
      germinationStartedDate = row.germinationStartedDate,
      hardeningOffQuantity = row.hardeningOffQuantity!!,
      id = row.id!!,
      initialBatchId = row.initialBatchId,
      latestObservedGerminatingQuantity = row.latestObservedGerminatingQuantity!!,
      latestObservedHardeningOffQuantity = row.latestObservedHardeningOffQuantity!!,
      latestObservedNotReadyQuantity = row.latestObservedActiveGrowthQuantity!!,
      latestObservedReadyQuantity = row.latestObservedReadyQuantity!!,
      latestObservedTime = row.latestObservedTime!!,
      lossRate = row.lossRate,
      notes = row.notes,
      notReadyQuantity = row.activeGrowthQuantity!!,
      organizationId = row.organizationId!!,
      projectId = row.projectId,
      readyByDate = row.readyByDate,
      readyQuantity = row.readyQuantity!!,
      seedsSownDate = row.seedsSownDate,
      speciesId = row.speciesId!!,
      subLocationIds = subLocationIds,
      substrate = row.substrateId,
      substrateNotes = row.substrateNotes,
      totalWithdrawn = totalWithdrawn,
      treatment = row.treatmentId,
      treatmentNotes = row.treatmentNotes,
      version = row.version!!,
  )
}
