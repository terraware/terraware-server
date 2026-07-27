package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.FieldsUpdatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.i18n.currentLocale
import com.terraformation.backend.seedbank.model.SeedQuantityModel
import java.time.LocalDate

sealed interface WithdrawalPersistentEvent : PersistentEvent {
  val withdrawalId: WithdrawalId
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
}

/** Published when a withdrawal is added to an accession. */
data class WithdrawalCreatedEventV1(
    val purpose: WithdrawalPurpose? = null,
    val date: LocalDate,
    val withdrawnQuantity: SeedQuantityModel? = null,
    val batchId: BatchId? = null,
    val notes: String? = null,
    val staffResponsible: String? = null,
    override val withdrawalId: WithdrawalId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, WithdrawalPersistentEvent

typealias WithdrawalCreatedEvent = WithdrawalCreatedEventV1

/** Published when the user edits an existing withdrawal. */
data class WithdrawalUpdatedEventV1(
    val changedFrom: Values,
    val changedTo: Values,
    override val withdrawalId: WithdrawalId,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : FieldsUpdatedPersistentEvent, WithdrawalPersistentEvent {
  data class Values(
      val date: LocalDate? = null,
      val withdrawnQuantity: SeedQuantityModel? = null,
      val purpose: WithdrawalPurpose? = null,
      val destination: String? = null,
      val notes: String? = null,
      val staffResponsible: String? = null,
      val withdrawnByUserId: UserId? = null,
  )

  override fun listUpdatedFields(messages: Messages) =
      listOfNotNull(
          createUpdatedField("date", changedFrom.date?.toString(), changedTo.date?.toString()),
          createUpdatedField(
              "withdrawnQuantity",
              changedFrom.withdrawnQuantity?.toString(),
              changedTo.withdrawnQuantity?.toString(),
          ),
          createUpdatedField(
              "purpose",
              changedFrom.purpose?.getDisplayName(currentLocale()),
              changedTo.purpose?.getDisplayName(currentLocale()),
          ),
          createUpdatedField("destination", changedFrom.destination, changedTo.destination),
          createUpdatedField("notes", changedFrom.notes, changedTo.notes),
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
      )
}

typealias WithdrawalUpdatedEvent = WithdrawalUpdatedEventV1

typealias WithdrawalUpdatedEventValues = WithdrawalUpdatedEventV1.Values
