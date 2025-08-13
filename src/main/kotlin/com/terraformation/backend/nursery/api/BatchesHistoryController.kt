package com.terraformation.backend.nursery.api

import com.fasterxml.jackson.annotation.JsonIgnore
import com.terraformation.backend.api.ApiResponse200
import com.terraformation.backend.api.ApiResponse404
import com.terraformation.backend.api.NurseryEndpoint
import com.terraformation.backend.api.SuccessResponsePayload
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedTreatment
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchDetailsHistoryId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.nursery.BatchQuantityHistoryType
import com.terraformation.backend.db.nursery.BatchSubstrate
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchDetailsHistorySubLocationsDao
import com.terraformation.backend.db.nursery.tables.daos.BatchPhotosDao
import com.terraformation.backend.db.nursery.tables.daos.BatchQuantityHistoryDao
import com.terraformation.backend.db.nursery.tables.daos.BatchWithdrawalsDao
import com.terraformation.backend.db.nursery.tables.daos.WithdrawalsDao
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchDetailsHistorySubLocationsRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchQuantityHistoryRow
import com.terraformation.backend.db.nursery.tables.pojos.BatchWithdrawalsRow
import com.terraformation.backend.db.nursery.tables.pojos.WithdrawalsRow
import com.terraformation.backend.log.perClassLogger
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@NurseryEndpoint
@RestController
@RequestMapping("/api/v1/nursery/batches/{batchId}/history")
class BatchesHistoryController(
    private val batchDetailsHistoryDao: BatchDetailsHistoryDao,
    private val batchDetailsHistorySubLocationsDao: BatchDetailsHistorySubLocationsDao,
    private val batchPhotosDao: BatchPhotosDao,
    private val batchQuantityHistoryDao: BatchQuantityHistoryDao,
    private val batchWithdrawalsDao: BatchWithdrawalsDao,
    private val withdrawalsDao: WithdrawalsDao,
) {
  private val log = perClassLogger()

  @ApiResponse200
  @ApiResponse404
  @GetMapping
  @Operation(
      summary = "Gets the history of changes to a seedling batch.",
      description =
          "Each event includes a version number. For events such as details edits that are " +
              "snapshots of the values at a particular time, clients can compare against the " +
              "event with the previous version number to see what has changed, e.g., to show " +
              "a delta or a diff view.")
  fun getBatchHistory(@PathVariable batchId: BatchId): GetBatchHistoryResponsePayload {
    requirePermissions { readBatch(batchId) }

    val detailsHistoryRows = batchDetailsHistoryDao.fetchByBatchId(batchId)
    val detailsSubLocations: Map<BatchDetailsHistoryId, List<BatchDetailsHistorySubLocationsRow>> =
        batchDetailsHistorySubLocationsDao
            .fetchByBatchDetailsHistoryId(*detailsHistoryRows.map { it.id!! }.toTypedArray())
            .groupBy { it.batchDetailsHistoryId!! }
    val detailsPayloads =
        detailsHistoryRows.map { detailsHistoryRow ->
          BatchHistoryDetailsEditedPayload(
              detailsHistoryRow, detailsSubLocations[detailsHistoryRow.id])
        }

    val incomingBatchWithdrawals =
        batchWithdrawalsDao.fetchByDestinationBatchId(batchId).associateBy { it.withdrawalId!! }
    val incomingWithdrawals =
        withdrawalsDao.fetchById(*incomingBatchWithdrawals.keys.toTypedArray()).associateBy {
          it.id!!
        }
    val outgoingBatchWithdrawals =
        batchWithdrawalsDao.fetchByBatchId(batchId).associateBy { it.withdrawalId!! }
    val outgoingWithdrawals =
        withdrawalsDao.fetchById(*outgoingBatchWithdrawals.keys.toTypedArray()).associateBy {
          it.id!!
        }

    val quantityHistory = batchQuantityHistoryDao.fetchByBatchId(batchId)
    val quantityPayloads =
        quantityHistory.map { quantityHistoryRow ->
          val withdrawalId = quantityHistoryRow.withdrawalId
          val incomingBatchWithdrawal = incomingBatchWithdrawals[withdrawalId]
          val incomingWithdrawal =
              incomingBatchWithdrawal?.withdrawalId?.let { incomingWithdrawals[it] }
          val outgoingBatchWithdrawal = outgoingBatchWithdrawals[withdrawalId]
          val outgoingWithdrawal =
              outgoingBatchWithdrawal?.withdrawalId?.let { outgoingWithdrawals[it] }

          when {
            incomingBatchWithdrawal != null && incomingWithdrawal != null -> {
              BatchHistoryIncomingWithdrawalPayload(
                  quantityHistoryRow, incomingBatchWithdrawal, incomingWithdrawal)
            }
            outgoingBatchWithdrawal != null && outgoingWithdrawal != null -> {
              BatchHistoryOutgoingWithdrawalPayload(
                  quantityHistoryRow, outgoingBatchWithdrawal, outgoingWithdrawal)
            }
            quantityHistoryRow.historyTypeId == BatchQuantityHistoryType.StatusChanged -> {
              BatchHistoryStatusChangedPayload(quantityHistoryRow)
            }
            else -> {
              BatchHistoryQuantityEditedPayload(quantityHistoryRow)
            }
          }
        }

    val batchPhotos = batchPhotosDao.fetchByBatchId(batchId)
    val photoCreatedPayloads =
        batchPhotos.map { batchPhotosRow ->
          BatchHistoryPhotoCreatedPayload(
              batchPhotosRow.createdBy!!, batchPhotosRow.createdTime!!, batchPhotosRow.fileId)
        }
    val photoDeletedPayloads =
        batchPhotos
            .filter { it.deletedTime != null }
            .map { batchPhotosRow ->
              BatchHistoryPhotoDeletedPayload(
                  batchPhotosRow.deletedBy!!, batchPhotosRow.deletedTime!!)
            }

    val historyPayloads =
        (detailsPayloads + quantityPayloads + photoCreatedPayloads + photoDeletedPayloads)
            .sortedWith { a, b ->
              if (a.version != null && b.version != null) {
                a.version!! - b.version!!
              } else {
                a.createdTime.compareTo(b.createdTime)
              }
            }

    return GetBatchHistoryResponsePayload(historyPayloads)
  }
}

sealed interface BatchHistoryPayloadCommonProps {
  val createdBy: UserId
  val createdTime: Instant
  val version: Int?
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    discriminatorMapping =
        [
            DiscriminatorMapping(
                schema = BatchHistoryDetailsEditedPayload::class, value = "DetailsEdited"),
            DiscriminatorMapping(
                schema = BatchHistoryIncomingWithdrawalPayload::class,
                value = "IncomingWithdrawal"),
            DiscriminatorMapping(
                schema = BatchHistoryOutgoingWithdrawalPayload::class,
                value = "OutgoingWithdrawal"),
            DiscriminatorMapping(
                schema = BatchHistoryPhotoCreatedPayload::class, value = "PhotoCreated"),
            DiscriminatorMapping(
                schema = BatchHistoryPhotoDeletedPayload::class, value = "PhotoDeleted"),
            DiscriminatorMapping(
                schema = BatchHistoryQuantityEditedPayload::class, value = "QuantityEdited"),
            DiscriminatorMapping(
                schema = BatchHistoryStatusChangedPayload::class, value = "StatusChanged"),
        ],
    discriminatorProperty = "type",
    oneOf =
        [
            BatchHistoryDetailsEditedPayload::class,
            BatchHistoryIncomingWithdrawalPayload::class,
            BatchHistoryOutgoingWithdrawalPayload::class,
            BatchHistoryPhotoCreatedPayload::class,
            BatchHistoryPhotoDeletedPayload::class,
            BatchHistoryQuantityEditedPayload::class,
            BatchHistoryStatusChangedPayload::class,
        ],
    type = "object",
)
sealed interface BatchHistoryPayload : BatchHistoryPayloadCommonProps

data class BatchHistorySubLocationPayload(
    @Schema(
        description =
            "The ID of the sub-location if it still exists. If it was subsequently deleted, this " +
                "will be null but the name will still be present.")
    val id: SubLocationId?,
    @Schema(
        description =
            "The name of the sub-location at the time the details were edited. If the " +
                "sub-location was subsequently renamed or deleted, this name remains the same.")
    val name: String,
) {
  constructor(
      row: BatchDetailsHistorySubLocationsRow
  ) : this(row.subLocationId, row.subLocationName!!)
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    description = "A change to the non-quantity-related details of a batch.")
data class BatchHistoryDetailsEditedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
    val germinationStartedDate: LocalDate?,
    val notes: String?,
    @Schema(
        description =
            "The ID of the batch's project if the project still exists. If the project was " +
                "subsequently deleted, this will be null but the project name will still be set.")
    val projectId: ProjectId?,
    @Schema(
        description =
            "The name of the project at the time the details were edited. If the project was " +
                "subsequently renamed or deleted, this name remains the same.")
    val projectName: String?,
    val readyByDate: LocalDate?,
    val seedsSownDate: LocalDate?,
    val subLocations: List<BatchHistorySubLocationPayload>,
    val substrate: BatchSubstrate?,
    val substrateNotes: String?,
    val treatment: SeedTreatment?,
    val treatmentNotes: String?,
    override val version: Int,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  constructor(
      row: BatchDetailsHistoryRow,
      subLocationRows: Collection<BatchDetailsHistorySubLocationsRow>?
  ) : this(
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      germinationStartedDate = row.germinationStartedDate,
      notes = row.notes,
      projectId = row.projectId,
      projectName = row.projectName,
      readyByDate = row.readyByDate,
      seedsSownDate = row.seedsSownDate,
      subLocations = subLocationRows?.map { BatchHistorySubLocationPayload(it) } ?: emptyList(),
      substrate = row.substrateId,
      substrateNotes = row.substrateNotes,
      treatment = row.treatmentId,
      treatmentNotes = row.treatmentNotes,
      version = row.version!!,
  )

  val type
    @Schema(allowableValues = ["DetailsEdited"]) get() = "DetailsEdited"
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    description = "A manual edit of a batch's remaining quantities.")
data class BatchHistoryQuantityEditedPayload(
    val activeGrowthQuantity: Int,
    override val createdBy: UserId,
    override val createdTime: Instant,
    val germinatingQuantity: Int,
    val hardeningOffQuantity: Int,
    val readyQuantity: Int,
    override val version: Int,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  constructor(
      row: BatchQuantityHistoryRow
  ) : this(
      activeGrowthQuantity = row.activeGrowthQuantity!!,
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      germinatingQuantity = row.germinatingQuantity!!,
      hardeningOffQuantity = row.hardeningOffQuantity!!,
      readyQuantity = row.readyQuantity!!,
      version = row.version!!,
  )

  val notReadyQuantity: Int // for backwards compatibility in response payloads
    get() = activeGrowthQuantity

  val type
    @Schema(allowableValues = ["QuantityEdited"]) get() = "QuantityEdited"
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    description =
        "The new quantities resulting from changing the statuses of seedlings in a batch. The " +
            "values here are the total quantities remaining after the status change, not the " +
            "number of seedlings whose statuses were changed.")
data class BatchHistoryStatusChangedPayload(
    val activeGrowthQuantity: Int,
    override val createdBy: UserId,
    override val createdTime: Instant,
    val germinatingQuantity: Int,
    val hardeningOffQuantity: Int = 0,
    val readyQuantity: Int,
    override val version: Int,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  constructor(
      row: BatchQuantityHistoryRow
  ) : this(
      activeGrowthQuantity = row.activeGrowthQuantity!!,
      createdBy = row.createdBy!!,
      createdTime = row.createdTime!!,
      germinatingQuantity = row.germinatingQuantity!!,
      hardeningOffQuantity = row.hardeningOffQuantity!!,
      readyQuantity = row.readyQuantity!!,
      version = row.version!!,
  )

  val notReadyQuantity: Int // for backwards compatibility in response payloads
    get() = activeGrowthQuantity

  val type
    @Schema(allowableValues = ["StatusChanged"]) get() = "StatusChanged"
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    description =
        "A nursery transfer withdrawal from another batch that added seedlings to this batch.")
data class BatchHistoryIncomingWithdrawalPayload(
    val activeGrowthQuantityAdded: Int,
    override val createdBy: UserId,
    override val createdTime: Instant,
    val fromBatchId: BatchId,
    val germinatingQuantityAdded: Int,
    val hardeningOffQuantity: Int = 0,
    val readyQuantityAdded: Int,
    override val version: Int,
    val withdrawalId: WithdrawalId,
    val withdrawnDate: LocalDate,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  constructor(
      historyRow: BatchQuantityHistoryRow,
      batchWithdrawalsRow: BatchWithdrawalsRow,
      withdrawalsRow: WithdrawalsRow,
  ) : this(
      activeGrowthQuantityAdded = batchWithdrawalsRow.activeGrowthQuantityWithdrawn!!,
      createdBy = historyRow.createdBy!!,
      createdTime = historyRow.createdTime!!,
      fromBatchId = batchWithdrawalsRow.batchId!!,
      germinatingQuantityAdded = batchWithdrawalsRow.germinatingQuantityWithdrawn!!,
      hardeningOffQuantity = batchWithdrawalsRow.hardeningOffQuantityWithdrawn!!,
      readyQuantityAdded = batchWithdrawalsRow.readyQuantityWithdrawn!!,
      version = historyRow.version!!,
      withdrawalId = historyRow.withdrawalId!!,
      withdrawnDate = withdrawalsRow.withdrawnDate!!,
  )

  val notReadyQuantityAdded: Int // for backwards compatibility in response payloads
    get() = activeGrowthQuantityAdded

  val type
    @Schema(allowableValues = ["IncomingWithdrawal"]) get() = "IncomingWithdrawal"
}

@Schema(
    allOf = [BatchHistoryPayloadCommonProps::class],
    description =
        "A withdrawal that removed seedlings from this batch. This does not include the full " +
            "details of the withdrawal; they can be retrieved using the withdrawal ID.")
data class BatchHistoryOutgoingWithdrawalPayload(
    val activeGrowthQuantityWithdrawn: Int,
    override val createdBy: UserId,
    override val createdTime: Instant,
    val germinatingQuantityWithdrawn: Int,
    val hardeningOffQuantity: Int = 0,
    val purpose: WithdrawalPurpose,
    val readyQuantityWithdrawn: Int,
    override val version: Int,
    val withdrawalId: WithdrawalId,
    val withdrawnDate: LocalDate,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  constructor(
      historyRow: BatchQuantityHistoryRow,
      batchWithdrawalsRow: BatchWithdrawalsRow,
      withdrawalsRow: WithdrawalsRow
  ) : this(
      activeGrowthQuantityWithdrawn = batchWithdrawalsRow.activeGrowthQuantityWithdrawn!!,
      createdBy = historyRow.createdBy!!,
      createdTime = historyRow.createdTime!!,
      germinatingQuantityWithdrawn = batchWithdrawalsRow.germinatingQuantityWithdrawn!!,
      hardeningOffQuantity = batchWithdrawalsRow.hardeningOffQuantityWithdrawn!!,
      purpose = withdrawalsRow.purposeId!!,
      readyQuantityWithdrawn = batchWithdrawalsRow.readyQuantityWithdrawn!!,
      version = historyRow.version!!,
      withdrawalId = batchWithdrawalsRow.withdrawalId!!,
      withdrawnDate = withdrawalsRow.withdrawnDate!!,
  )

  val notReadyQuantityWithdrawn: Int // for backwards compatibility in response payloads
    get() = activeGrowthQuantityWithdrawn

  val type
    @Schema(allowableValues = ["OutgoingWithdrawal"]) get() = "OutgoingWithdrawal"
}

@Schema(allOf = [BatchHistoryPayloadCommonProps::class])
data class BatchHistoryPhotoCreatedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
    @Schema(description = "ID of the photo if it exists. Null if the photo has been deleted.")
    val fileId: FileId?,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {

  val type
    @Schema(allowableValues = ["PhotoCreated"]) get() = "PhotoCreated"

  override val version
    @JsonIgnore get() = null
}

@Schema(allOf = [BatchHistoryPayloadCommonProps::class])
data class BatchHistoryPhotoDeletedPayload(
    override val createdBy: UserId,
    override val createdTime: Instant,
) : BatchHistoryPayload, BatchHistoryPayloadCommonProps {
  val type
    @Schema(allowableValues = ["PhotoDeleted"]) get() = "PhotoDeleted"

  override val version
    @JsonIgnore get() = null
}

data class GetBatchHistoryResponsePayload(val history: List<BatchHistoryPayload>) :
    SuccessResponsePayload
