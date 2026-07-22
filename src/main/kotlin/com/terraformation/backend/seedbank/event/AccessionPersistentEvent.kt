package com.terraformation.backend.seedbank.event

import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.DataSource
import com.terraformation.backend.eventlog.EntityCreatedPersistentEvent
import com.terraformation.backend.eventlog.PersistentEvent

sealed interface AccessionPersistentEvent : PersistentEvent {
  val accessionId: AccessionId
  val facilityId: FacilityId
  val organizationId: OrganizationId
}

/** Published when an accession is created manually via the web app or the seed collector app. */
data class AccessionCreatedEventV1(
    val accessionNumber: String,
    val dataSource: DataSource,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, AccessionPersistentEvent

typealias AccessionCreatedEvent = AccessionCreatedEventV1

/** Published when an accession is created as part of a CSV import. */
data class AccessionUploadedEventV1(
    val accessionNumber: String,
    override val accessionId: AccessionId,
    override val facilityId: FacilityId,
    override val organizationId: OrganizationId,
) : EntityCreatedPersistentEvent, AccessionPersistentEvent

typealias AccessionUploadedEvent = AccessionUploadedEventV1
