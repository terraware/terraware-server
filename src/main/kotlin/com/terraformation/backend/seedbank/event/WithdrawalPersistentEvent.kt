package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.BatchId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.seedbank.WithdrawalPurpose
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent
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
