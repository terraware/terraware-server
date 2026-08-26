package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.EventLogId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.eventlog.EntityDeletedPersistentEvent
import com.terraformation.backend.eventlog.FieldsCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.eventlog.UpgradableEvent
import com.terraformation.backend.eventlog.db.EventUpgradeUtils
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import java.time.LocalDate

sealed interface WithdrawalPersistentEvent : PersistentEvent {
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
  val withdrawalId: WithdrawalId
}

data class WithdrawalCreatedEventV1(
    override val accessionId: AccessionId,
    val batchId: BatchId? = null,
    val date: LocalDate,
    override val facilityId: FacilityId,
    val notes: String? = null,
    override val organizationId: OrganizationId,
    val purpose: WithdrawalPurpose? = null,
    val staffResponsible: String? = null,
    override val withdrawalId: WithdrawalId,
    val withdrawnQuantity: SeedQuantityModel? = null,
) : UpgradableEvent, WithdrawalPersistentEvent {
  override fun toNextVersion(
      eventLogId: EventLogId,
      eventUpgradeUtils: EventUpgradeUtils,
  ): WithdrawalCreatedEventV2 = eventUpgradeUtils.upgrade(this)
}

/** Published when a withdrawal is added to an accession. */
data class WithdrawalCreatedEventV2(
    override val accessionId: AccessionId,
    val batchId: BatchId? = null,
    val date: LocalDate,
    val destination: String? = null,
    override val facilityId: FacilityId,
    val notes: String? = null,
    override val organizationId: OrganizationId,
    val purpose: WithdrawalPurpose? = null,
    val staffResponsible: String? = null,
    val viabilityTestId: ViabilityTestId? = null,
    override val withdrawalId: WithdrawalId,
    val withdrawnByUserId: UserId? = null,
    val withdrawnQuantity: SeedQuantityModel? = null,
) : FieldsCreatedPersistentEvent, WithdrawalPersistentEvent {
  override fun listInitialFields(messages: Messages) =
      listOfNotNull(
          createInitialField("batchId", batchId?.toString()),
          createInitialField("date", date.toString()),
          createInitialField("destination", destination),
          createInitialField("notes", notes),
          createInitialField("purpose", purpose?.getDisplayName(currentLocale())),
          createInitialField("staffResponsible", staffResponsible),
          createInitialField("viabilityTestId", viabilityTestId?.toString()),
          createInitialField("withdrawnByUserId", withdrawnByUserId?.toString()),
          createInitialField("withdrawnQuantity", withdrawnQuantity?.toString()),
      )
}

typealias WithdrawalCreatedEvent = WithdrawalCreatedEventV2

/** Published when the user edits an existing withdrawal. */
data class WithdrawalUpdatedEventV1(
    override val accessionId: AccessionId,
    val changedFrom: Values,
    val changedTo: Values,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    override val withdrawalId: WithdrawalId,
) : FieldsUpdatedPersistentEvent, WithdrawalPersistentEvent {
  data class Values(
      val date: LocalDate? = null,
      val destination: String? = null,
      val notes: String? = null,
      val purpose: WithdrawalPurpose? = null,
      val staffResponsible: String? = null,
      val withdrawnByUserId: UserId? = null,
      val withdrawnQuantity: SeedQuantityModel? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("date", changedFrom.date?.toString(), changedTo.date?.toString()),
          createUpdatedField("destination", changedFrom.destination, changedTo.destination),
          createUpdatedField("notes", changedFrom.notes, changedTo.notes),
          createUpdatedField(
              "purpose",
              changedFrom.purpose?.getDisplayName(currentLocale()),
              changedTo.purpose?.getDisplayName(currentLocale()),
          ),
          createUpdatedField(
              "staffResponsible",
              changedFrom.staffResponsible,
              changedTo.staffResponsible,
          ),
          createUpdatedField(
              "withdrawnByUserId",
              changedFrom.withdrawnByUserId?.toString(),
              changedTo.withdrawnByUserId?.toString(),
          ),
          createUpdatedField(
              "withdrawnQuantity",
              changedFrom.withdrawnQuantity?.toString(),
              changedTo.withdrawnQuantity?.toString(),
          ),
      )
}

typealias WithdrawalUpdatedEvent = WithdrawalUpdatedEventV1

typealias WithdrawalUpdatedEventValues = WithdrawalUpdatedEventV1.Values

/** Published when a withdrawal is deleted from an accession. */
data class WithdrawalDeletedEventV1(
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
    override val withdrawalId: WithdrawalId,
) : EntityDeletedPersistentEvent, WithdrawalPersistentEvent

typealias WithdrawalDeletedEvent = WithdrawalDeletedEventV1
